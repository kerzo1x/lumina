#version 460

// Replaces the game's sky: atmosphere, sun, moon with phases, stars, clouds, and the End's nebula.
// Drawn as one screen-covering triangle where the game would draw its sky, before any terrain.

layout (binding = 0) uniform SkyUBO {
    // Rotation from view space to world axes
    mat4 LuminaViewToWorld;
    // Rotation from world axes to the frame the stars are fixed in
    mat4 LuminaCelestial;
    // x: 1 / projection[0][0], y: 1 / projection[1][1]
    vec4 LuminaProj;
    // xyz: direction to the sun, w: how clear the sky is (1 = no rain)
    vec4 LuminaSunDir;
    // xyz: direction to the moon, w: phase, 0 = full, 4 = new
    vec4 LuminaMoonDir;
    // rgb: the game's sky colour for this biome and time, a: 1 = overworld, 2 = End
    vec4 LuminaSkyColor;
    // rgb: the game's sunrise/sunset colour, a: its strength
    vec4 LuminaSunsetColor;
    // x: star brightness, y: cloud coverage (0 = no clouds), z: rain, w: thunder
    vec4 LuminaSkyParams;
    // xz: position clouds are sampled at (camera plus wind drift), y: camera height, w: time in seconds
    vec4 LuminaSkyOrigin;
    // rgb: colour of direct light, a: its strength
    vec4 LuminaLitColor;
    // What distant terrain fades into; the horizon has to match it
    vec4 FogColor;
};

layout (location = 0) in vec2 texCoord;

layout (location = 0) out vec4 fragColor;

const float CLOUD_HEIGHT = 192.0;

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float hash31(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

float noise2(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);

    return mix(mix(hash21(i), hash21(i + vec2(1.0, 0.0)), f.x),
               mix(hash21(i + vec2(0.0, 1.0)), hash21(i + vec2(1.0, 1.0)), f.x), f.y);
}

float noise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);

    return mix(mix(mix(hash31(i), hash31(i + vec3(1.0, 0.0, 0.0)), f.x),
                   mix(hash31(i + vec3(0.0, 1.0, 0.0)), hash31(i + vec3(1.0, 1.0, 0.0)), f.x), f.y),
               mix(mix(hash31(i + vec3(0.0, 0.0, 1.0)), hash31(i + vec3(1.0, 0.0, 1.0)), f.x),
                   mix(hash31(i + vec3(0.0, 1.0, 1.0)), hash31(i + vec3(1.0, 1.0, 1.0)), f.x), f.y), f.z);
}

float fbm2(vec2 p) {
    float sum = 0.0;
    float amplitude = 0.5;

    for (int i = 0; i < 5; ++i) {
        sum += amplitude * noise2(p);
        p = p * 2.03 + vec2(17.1, 9.7);
        amplitude *= 0.5;
    }

    return sum;
}

float fbm3(vec3 p) {
    float sum = 0.0;
    float amplitude = 0.5;

    for (int i = 0; i < 4; ++i) {
        sum += amplitude * noise3(p);
        p = p * 2.07 + vec3(11.3, 5.9, 7.1);
        amplitude *= 0.5;
    }

    return sum;
}

// Points of light scattered over the sphere of directions. density is the share of cells holding a star.
float starField(vec3 dir, float scale, float density, float time) {
    vec3 p = dir * scale;
    vec3 cell = floor(p);
    float seed = hash31(cell);

    if (seed > density)
        return 0.0;

    vec3 offset = vec3(hash31(cell + 1.3), hash31(cell + 2.7), hash31(cell + 4.1)) - 0.5;
    float distanceToStar = length(fract(p) - 0.5 - offset * 0.6);
    float brightness = 0.35 + 0.65 * fract(seed * 937.0);
    float twinkle = 0.75 + 0.25 * sin(time * 3.0 + seed * 600.0);

    return smoothstep(0.2, 0.0, distanceToStar) * brightness * twinkle;
}

vec3 moon(vec3 dir, vec3 moonDir, float phase) {
    const float RADIUS = 0.036;

    vec3 right = normalize(cross(moonDir, vec3(0.0, 0.0, 1.0)));
    vec3 up = cross(right, moonDir);
    vec2 disc = vec2(dot(dir, right), dot(dir, up)) / RADIUS;
    float r2 = dot(disc, disc);

    float facing = dot(dir, moonDir);
    vec3 glow = vec3(0.55, 0.65, 0.9) * pow(max(facing, 0.0), 300.0) * 0.18 * abs(phase - 4.0) / 4.0;

    if (facing <= 0.0 || r2 >= 1.0)
        return glow;

    // Shade the disc as a sphere lit from the side: the angle of that light is the phase
    vec3 normal = vec3(disc, sqrt(1.0 - r2));
    float angle = phase * 0.7853982;
    float lit = smoothstep(-0.05, 0.2, dot(normal, vec3(sin(angle), 0.0, cos(angle))));

    float maria = 0.78 + 0.22 * noise2(disc * 3.0 + 7.0) - 0.12 * smoothstep(0.55, 0.75, noise2(disc * 1.7 + 2.0));
    vec3 surface = vec3(0.88, 0.90, 0.96) * maria * (0.04 + 0.96 * lit);

    return glow + surface * smoothstep(1.0, 0.9, r2);
}

vec3 overworld(vec3 dir) {
    float time = LuminaSkyOrigin.w;
    vec3 sunDir = LuminaSunDir.xyz;
    vec3 moonDir = LuminaMoonDir.xyz;
    float clear = LuminaSunDir.w;
    float rain = LuminaSkyParams.z;
    float thunder = LuminaSkyParams.w;

    vec3 horizon = FogColor.rgb;
    vec3 zenith = LuminaSkyColor.rgb;

    // Deeper blue straight up, the game's fog colour at and below the horizon
    vec3 deep = zenith * vec3(0.62, 0.76, 1.0);
    vec3 sky = mix(horizon, mix(zenith, deep, smoothstep(0.15, 0.95, dir.y)), smoothstep(-0.02, 0.5, dir.y));

    // Sunrise and sunset: a band of the game's own dawn colour, strongest towards the sun
    float towardsSun = max(dot(normalize(dir.xz + vec2(1.0e-5)), normalize(sunDir.xz + vec2(1.0e-5))), 0.0);
    float band = exp(-max(dir.y, 0.0) * 4.5) * smoothstep(-0.3, 0.0, dir.y);
    sky += LuminaSunsetColor.rgb * LuminaSunsetColor.a * band * (0.3 + 0.7 * towardsSun * towardsSun) * clear;

    float aboveHorizon = smoothstep(-0.06, 0.0, dir.y);

    // Stars and a faint band of the galaxy, turning with the night
    float starLight = LuminaSkyParams.x * clear;
    if (starLight > 0.01 && dir.y > -0.06) {
        vec3 fixedDir = mat3(LuminaCelestial) * dir;
        float stars = starField(fixedDir, 90.0, 0.012, time) + starField(fixedDir, 42.0, 0.006, time) * 1.6;
        float galaxy = exp(-fixedDir.z * fixedDir.z * 14.0) * fbm2(fixedDir.xy * 5.0 + 3.0);

        // The game's night sky is close to black: a little blue keeps it a sky rather than a void
        sky += vec3(0.010, 0.018, 0.045) * (0.5 + 0.5 * dir.y) * starLight * 2.0;
        sky += (vec3(0.9, 0.95, 1.0) * stars * 2.2 + vec3(0.25, 0.3, 0.5) * galaxy * 0.5) * starLight * aboveHorizon;
    }

    // Sun: a small bright disc with a wide soft halo
    float sunFacing = dot(dir, sunDir);
    vec3 sunColor = mix(vec3(1.0, 0.5, 0.2), vec3(1.0, 0.96, 0.86), smoothstep(0.0, 0.35, sunDir.y));
    float sunDisc = smoothstep(0.99925, 0.99955, sunFacing);
    float sunHalo = pow(max(sunFacing, 0.0), 400.0) * 0.55 + pow(max(sunFacing, 0.0), 28.0) * 0.14;
    sky += sunColor * (sunDisc * 4.0 + sunHalo) * clear * aboveHorizon;

    sky += moon(dir, moonDir, LuminaMoonDir.w) * clear * aboveHorizon;

    // Clouds: a flat layer of noise at cloud height, lit from the side the light is on
    float coverage = LuminaSkyParams.y;
    float heightToClouds = CLOUD_HEIGHT - LuminaSkyOrigin.y;
    float distanceToClouds = heightToClouds / (abs(dir.y) < 1.0e-4 ? 1.0e-4 : dir.y);

    if (coverage > 0.0 && distanceToClouds > 0.0 && distanceToClouds < 9000.0) {
        vec2 p = (LuminaSkyOrigin.xz + dir.xz * distanceToClouds) * 0.0024;

        // fbm2 tops out a little below 1, hence the division
        float shape = (fbm2(p) * 0.72 + fbm2(p * 2.9 + 31.0) * 0.28) / 0.97;
        float threshold = mix(0.68, 0.30, clamp(coverage + rain * 0.45, 0.0, 1.0));
        float density = smoothstep(threshold, threshold + 0.13, shape);

        if (density > 0.001) {
            // Less cloud between this point and the light means a brighter cloud
            vec3 lightDir = sunDir.y > moonDir.y ? sunDir : moonDir;
            vec2 towardsLight = normalize(lightDir.xz + vec2(1.0e-4)) * (1.0 - 0.6 * abs(lightDir.y));
            float shadowing = smoothstep(threshold - 0.05, threshold + 0.3, fbm2(p + towardsLight * 0.06) / 0.97);

            float daylight = clamp(dot(FogColor.rgb, vec3(0.3, 0.5, 0.2)) * 1.6, 0.0, 1.0);
            vec3 litCloud = mix(vec3(0.10, 0.12, 0.18), LuminaLitColor.rgb * vec3(1.0, 0.98, 0.96), daylight);
            vec3 darkCloud = mix(vec3(0.03, 0.035, 0.06), mix(zenith, vec3(0.62, 0.66, 0.74), 0.55) * 0.82, daylight);

            vec3 cloud = mix(litCloud, darkCloud, shadowing * 0.85);

            // Warm rim at dusk and dawn, heavy grey in bad weather
            cloud += LuminaSunsetColor.rgb * LuminaSunsetColor.a * 0.35 * (1.0 - shadowing) * towardsSun;
            cloud *= mix(1.0, 0.5, rain) * mix(1.0, 0.6, thunder);

            float fade = exp(-distanceToClouds * 0.00028) * smoothstep(0.0, 0.1, abs(dir.y));
            sky = mix(sky, cloud, density * fade * 0.96);
        }
    }

    return sky;
}

// A star of the given layer, tinted by its temperature: most are white, some blue, some orange
vec3 colouredStars(vec3 dir, float scale, float density, float time) {
    float light = starField(dir, scale, density, time);

    if (light <= 0.0)
        return vec3(0.0);

    float temperature = hash31(floor(dir * scale) + 9.1);
    vec3 tint = temperature < 0.2 ? vec3(0.65, 0.78, 1.0) : (temperature > 0.85 ? vec3(1.0, 0.72, 0.5) : vec3(1.0, 0.97, 0.92));

    return tint * light;
}

// The End: open space. Black, crowded with stars, crossed by the band of a galaxy with dust lanes,
// and a few faint clouds of glowing gas in the End's own purple.
vec3 endSky(vec3 dir) {
    float time = LuminaSkyOrigin.w;
    float brightness = LuminaSkyParams.x;

    vec3 sky = vec3(0.004, 0.004, 0.009);

    // Three depths of stars: a dust of faint ones, the ordinary ones, and a handful of bright ones
    vec3 stars = colouredStars(dir, 150.0, 0.10, time) * 0.9
               + colouredStars(dir, 85.0, 0.04, time) * 2.0
               + colouredStars(dir, 34.0, 0.008, time) * 3.5;

    // The galaxy lies along a tilted great circle
    vec3 pole = normalize(vec3(0.35, 0.62, -0.70));
    float latitude = dot(dir, pole);
    float band = exp(-latitude * latitude * 22.0);

    if (band > 0.01) {
        float clouds = fbm3(dir * 3.5 + 4.0) / 0.94;
        float dust = fbm3(dir * 8.0 + vec3(1.7, 9.2, 3.3)) / 0.94;

        // Dark lanes of dust run along the middle and swallow the light behind them
        float lanes = smoothstep(0.42, 0.62, dust) * exp(-latitude * latitude * 90.0);
        float glow = band * (0.35 + 0.65 * clouds) * (1.0 - 0.8 * lanes);

        // Warm towards the core, cooler along the arms
        float core = pow(max(dot(dir, normalize(cross(pole, vec3(0.0, 1.0, 0.0)))), 0.0), 3.0);
        sky += mix(vec3(0.20, 0.24, 0.36), vec3(0.50, 0.40, 0.28), core) * glow * 0.85;

        // Too many stars to tell apart, as a fine grain
        stars += vec3(0.9, 0.9, 1.0) * starField(dir, 260.0, 0.22, time) * band * (1.0 - lanes) * 0.9;
    }

    // Glowing gas, only in a few places
    float patches = smoothstep(0.52, 0.74, fbm3(dir * 1.3 + 11.0) / 0.94);
    if (patches > 0.0) {
        float gas = fbm3(dir * 4.2 + vec3(0.0, time * 0.002, 0.0)) / 0.94;
        float filaments = fbm3(dir * 9.0 + gas * 1.5 + 5.0) / 0.94;

        sky += vec3(0.36, 0.10, 0.52) * smoothstep(0.35, 0.75, gas) * patches * 0.42;
        sky += vec3(0.08, 0.30, 0.38) * smoothstep(0.50, 0.80, filaments) * patches * 0.26;
    }

    return sky + stars * brightness;
}

void main() {
    vec2 ndc = vec2(texCoord.x * 2.0 - 1.0, 1.0 - texCoord.y * 2.0);
    vec3 dir = normalize(mat3(LuminaViewToWorld) * vec3(ndc * LuminaProj.xy, -1.0));

    vec3 color = LuminaSkyColor.a > 1.5 ? endSky(dir) : overworld(dir);

    fragColor = vec4(color, 1.0);
}
