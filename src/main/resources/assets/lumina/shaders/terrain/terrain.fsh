#version 460

#include "terrain_common.glsl"

layout (binding = 4) uniform sampler2D Sampler0;
layout (binding = 6) uniform sampler2DShadow ShadowMap;
layout (binding = 7) uniform sampler2DShadow ShadowMapFar;
layout (binding = 8) uniform sampler2D SceneColor;
layout (binding = 9) uniform sampler2D SceneDepth;

layout (binding = 0) uniform UniformBufferObject {
    mat4 MVP;
};

layout (location = 0) in vec4 vertexColor;
layout (location = 1) in vec2 texCoord0;
layout (location = 2) in float sphericalVertexDistance;
layout (location = 3) in float cylindricalVertexDistance;
layout (location = 4) in flat float fadeFactor;
layout (location = 5) in vec3 lightColor;
layout (location = 6) in float skyLevel;
layout (location = 7) in vec3 relPos;
layout (location = 8) in flat float blockKind;

layout (location = 0) out vec4 fragColor;

vec4 sampleNearest(sampler2D sampler1, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv, vec2 texelScreenSize) {
    // Convert our UV back up to texel coordinates and find out how far over we are from the center of each pixel
    vec2 uvTexelCoords = uv / pixelSize;
    vec2 texelCenter = round(uvTexelCoords) - 0.5f;
    vec2 texelOffset = uvTexelCoords - texelCenter;

    // Move our offset closer to the texel center based on texel size on screen
    texelOffset = (texelOffset - 0.5f) * pixelSize / texelScreenSize + 0.5f;
    texelOffset = clamp(texelOffset, 0.0f, 1.0f);

    uv = (texelCenter + texelOffset) * pixelSize;
    return textureGrad(sampler1, uv, du, dv);
}

vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    return sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
}

// Rotated Grid Super-Sampling
vec4 sampleRGSS(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);

    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);

    float minPixelSize = min(pixelSize.x, pixelSize.y);

    float transitionStart = minPixelSize * 1.0;
    float transitionEnd = minPixelSize * 2.0;
    float blendFactor = smoothstep(transitionStart, transitionEnd, maxTexelSize);

    float duLength = length(du);
    float dvLength = length(dv);
    float minDerivative = min(duLength, dvLength);
    float maxDerivative = max(duLength, dvLength);

    float effectiveDerivative = sqrt(minDerivative * maxDerivative);

    float mipLevelExact = max(0.0, log2(effectiveDerivative / minPixelSize));

    const vec2 offsets[4] = vec2[](
    vec2(0.125, 0.375),
    vec2(-0.125, -0.375),
    vec2(0.375, -0.125),
    vec2(-0.375, 0.125)
    );

    float mipLevelLow = floor(mipLevelExact);
    float mipLevelHigh = mipLevelLow + 1.0;
    float mipBlend = fract(mipLevelExact);

    vec4 rgssColorLow = vec4(0.0);
    vec4 rgssColorHigh = vec4(0.0);
    for (int i = 0; i < 4; ++i) {
        vec2 sampleUV = uv + offsets[i] * pixelSize;
        rgssColorLow += textureLod(source, sampleUV, mipLevelLow);
        rgssColorHigh += textureLod(source, sampleUV, mipLevelHigh);
    }
    rgssColorLow *= 0.25;
    rgssColorHigh *= 0.25;

    vec4 rgssColor = mix(rgssColorLow, rgssColorHigh, mipBlend);

    vec4 nearestColor = sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);

    return mix(nearestColor, rgssColor, blendFactor);
}

float fogValue(float dist, float start, float end) {
    return dist <= start ? 0.0 : (dist >= end ? 1.0 : (dist - start) / (end - start));
}

// The face shading vanilla bakes into vertex colours, rebuilt from the normal so it can be blended away in sunlight
float vanillaShade(vec3 n) {
    vec3 n2 = n * n;
    return n2.x * 0.6 + n2.z * 0.8 + n2.y * (n.y > 0.0 ? 1.0 : 0.5);
}

float pcf(sampler2DShadow map, vec3 uv, float kernel) {
    vec2 step = kernel / vec2(textureSize(map, 0));

    float sum = texture(map, uv) * 2.0;
    sum += texture(map, uv + vec3(-step.x, -step.y, 0.0));
    sum += texture(map, uv + vec3( step.x, -step.y, 0.0));
    sum += texture(map, uv + vec3(-step.x,  step.y, 0.0));
    sum += texture(map, uv + vec3( step.x,  step.y, 0.0));
    sum += texture(map, uv + vec3(-1.5 * step.x, 0.0, 0.0));
    sum += texture(map, uv + vec3( 1.5 * step.x, 0.0, 0.0));
    sum += texture(map, uv + vec3(0.0, -1.5 * step.y, 0.0));
    sum += texture(map, uv + vec3(0.0,  1.5 * step.y, 0.0));

    return sum / 10.0;
}

// Shadow map coordinates of a point pushed off its surface, so the surface does not shadow itself.
// The push grows with the filter size and at grazing light angles.
vec3 shadowCoord(mat4 shadowMat, vec3 pos, vec3 normal, float ndl, float texel, float kernel) {
    float slope = clamp(1.0 - ndl, 0.0, 1.0);
    vec3 offsetPos = pos + normal * texel * kernel * (1.0 + 2.0 * slope);
    vec3 clip = (shadowMat * vec4(offsetPos, 1.0)).xyz;

    return vec3(clip.xy * 0.5 + 0.5, clip.z - 0.00006);
}

float sampleShadow(vec3 pos, vec3 normal, float ndl) {
    float softness = max(LuminaParams.y, 0.25);

    // The near cascade has much smaller texels, a wider kernel keeps its penumbra similar to the far one
    float nearKernel = softness * 2.5;
    float farKernel = softness;

    vec3 nearUv = shadowCoord(LuminaShadowMat, pos, normal, ndl, LuminaShadowParams.x, nearKernel);
    vec2 nearCentered = abs(nearUv.xy * 2.0 - 1.0);
    float toFar = smoothstep(0.82, 0.96, max(nearCentered.x, nearCentered.y));

    float shadow = 1.0;

    if (toFar < 1.0)
        shadow = pcf(ShadowMap, nearUv, nearKernel);

    if (toFar > 0.0) {
        float farShadow = 1.0;

        if (LuminaLightDir.w > 1.5) {
            vec3 farUv = shadowCoord(LuminaShadowMatFar, pos, normal, ndl, LuminaShadowParams.y, farKernel);

            if (farUv.x > 0.0 && farUv.x < 1.0 && farUv.y > 0.0 && farUv.y < 1.0 && farUv.z < 1.0)
                farShadow = pcf(ShadowMapFar, farUv, farKernel);
        }

        shadow = mix(shadow, farShadow, toFar);
    }

    return shadow;
}

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);

    return mix(mix(hash21(i), hash21(i + vec2(1.0, 0.0)), f.x),
               mix(hash21(i + vec2(0.0, 1.0)), hash21(i + vec2(1.0, 1.0)), f.x), f.y);
}

// Slope of the water surface: a few sine waves travelling in different directions
vec2 waterSlope(vec2 p, float time) {
    vec2 slope = vec2(0.0);
    slope += vec2(0.8, 0.6) * cos(dot(p, vec2(0.8, 0.6)) * 1.7 + time * 1.3) * 0.050;
    slope += vec2(-0.5, 0.86) * cos(dot(p, vec2(-0.5, 0.86)) * 2.9 - time * 1.7) * 0.035;
    slope += vec2(0.3, -0.95) * cos(dot(p, vec2(0.3, -0.95)) * 5.3 + time * 2.4) * 0.022;
    slope += vec2(-0.9, -0.4) * cos(dot(p, vec2(-0.9, -0.4)) * 9.1 - time * 3.1) * 0.012;
    return slope;
}

// SceneDepth holds the distance from the camera, with this or more meaning open sky
const float SKY_DISTANCE = 50000.0;

vec2 clipToScreen(vec4 clip) {
    vec2 ndc = clip.xy / clip.w;
    return vec2(ndc.x * 0.5 + 0.5, 0.5 - ndc.y * 0.5);
}

float interleavedNoise(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

// Screen-space reflection: walks along the mirrored ray until it disappears behind something already drawn.
// Returns the colour found there in rgb and how much to trust it in a.
vec4 traceReflection(vec3 origin, vec3 dir) {
    float stepLength = 0.5;
    float travelled = stepLength * (0.5 + interleavedNoise(gl_FragCoord.xy));
    vec2 uv = vec2(0.0);
    float depth = 0.0;

    for (int i = 0; i < 24; ++i) {
        vec4 clip = MVP * vec4(origin + dir * travelled, 1.0);

        if (clip.w <= 0.0)
            return vec4(0.0);

        uv = clipToScreen(clip);

        if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0)
            return vec4(0.0);

        depth = textureLod(SceneDepth, uv, 0.0).r;
        float behind = clip.w - depth;

        // Inside something, and not so deep that the ray merely passed behind a thin object in front
        if (depth < SKY_DISTANCE && behind > 0.0 && behind < stepLength * 2.0 + 1.5) {
            // Halve back towards the surface for a cleaner edge
            float low = travelled - stepLength;
            float high = travelled;

            for (int j = 0; j < 4; ++j) {
                float middle = (low + high) * 0.5;
                vec4 middleClip = MVP * vec4(origin + dir * middle, 1.0);
                vec2 middleUv = clipToScreen(middleClip);

                if (middleClip.w > textureLod(SceneDepth, middleUv, 0.0).r)
                    high = middle;
                else
                    low = middle;
            }

            uv = clipToScreen(MVP * vec4(origin + dir * high, 1.0));
            vec2 edge = min(uv, 1.0 - uv);
            float fade = smoothstep(0.0, 0.08, min(edge.x, edge.y));

            return vec4(textureLod(SceneColor, uv, 0.0).rgb, fade);
        }

        stepLength *= 1.3;
        travelled += stepLength;
    }

    // Ran out of steps over open sky: what is on screen there (clouds, sun, sunset) is the reflection
    if (depth >= SKY_DISTANCE) {
        vec2 edge = min(uv, 1.0 - uv);
        return vec4(textureLod(SceneColor, uv, 0.0).rgb, smoothstep(0.0, 0.08, min(edge.x, edge.y)));
    }

    return vec4(0.0);
}

// Water seen from above. surface is the lit water texture colour, sunLit how much direct sun reaches this spot.
vec4 shadeWater(vec3 surface, vec3 light, float skyAccess, float sunLit) {
    vec3 view = normalize(-relPos);
    float eyeDistance = 1.0 / gl_FragCoord.w;

    // Ripples fade with distance, where they would only alias
    vec2 slope = waterSlope(relPos.xz + LuminaCamera.xz, LuminaCamera.w) * LuminaReflect.w / (1.0 + eyeDistance * 0.06);
    vec3 waveNormal = normalize(vec3(-slope.x, 1.0, -slope.y));

    // What lies behind the surface, bent by the ripples. Shallow water bends less, so shorelines stay put
    vec2 screenUv = gl_FragCoord.xy / ScreenSize;
    float depthStraight = textureLod(SceneDepth, screenUv, 0.0).r - eyeDistance;
    vec2 bend = slope * 0.9 * clamp(depthStraight * 0.5, 0.0, 1.0) / (1.0 + eyeDistance * 0.15);

    float depthBent = textureLod(SceneDepth, screenUv + bend, 0.0).r - eyeDistance;
    if (depthBent < 0.0) {
        // The bent ray landed on something in front of the water
        bend = vec2(0.0);
        depthBent = depthStraight;
    }

    vec3 behind = textureLod(SceneColor, screenUv + bend, 0.0).rgb;
    float thickness = max(depthBent, 0.0);

    // Red is absorbed first, then green: shallow water is clear, deep water turns to its own colour
    vec3 body = behind * exp(-thickness * vec3(0.30, 0.11, 0.08));
    body = mix(body, surface * 0.85, 1.0 - exp(-thickness * 0.10));

    float cosView = max(dot(waveNormal, view), 0.0);
    float fresnel = 0.03 + 0.97 * pow(1.0 - cosView, 5.0);

    vec3 reflected = reflect(-view, waveNormal);
    reflected.y = max(reflected.y, 0.02);

    // Under a roof or in a cave there is no sky to fall back on
    vec3 sky = mix(FogColor.rgb, LuminaSky.rgb, sqrt(clamp(reflected.y, 0.0, 1.0))) * (0.4 + 0.6 * light);
    vec3 fallback = mix(body, sky, skyAccess);

    vec4 hit = traceReflection(relPos, normalize(reflected));
    vec3 reflection = mix(fallback, hit.rgb, hit.a);

    vec3 result = mix(body, reflection, fresnel);

    // Sun glint on the ripples
    result += LuminaLitColor.rgb * pow(max(dot(reflected, LuminaLightDir.xyz), 0.0), 220.0) * sunLit * 1.6;

    return vec4(result, 1.0);
}

// What a mirror-like surface with the given normal shows: whatever the reflected ray finds on screen,
// the sky where it finds nothing and the sky can be seen from here, the surface itself otherwise.
vec3 mirror(vec3 surface, vec3 normal, float skyAccess) {
    vec3 reflected = reflect(normalize(relPos), normal);
    vec3 sky = mix(FogColor.rgb, LuminaSky.rgb, sqrt(clamp(reflected.y, 0.0, 1.0)));
    vec4 hit = traceReflection(relPos, reflected);

    return mix(mix(surface, sky, skyAccess), hit.rgb, hit.a);
}

float fresnel(vec3 normal, float base) {
    float cosView = max(dot(normal, normalize(-relPos)), 0.0);
    return base + (1.0 - base) * pow(1.0 - cosView, 5.0);
}

// Lava lights itself: slow patches of cooler crust and hotter melt drift across it, the hottest pushed past white
// so the bloom picks them up
vec3 shadeLava(vec3 base) {
    vec2 p = relPos.xz + LuminaCamera.xz + vec2(0.0, (relPos.y + LuminaCamera.y) * 0.7);
    float time = LuminaCamera.w;

    float slow = valueNoise(p * 0.55 + vec2(time * 0.05, -time * 0.04));
    float fine = valueNoise(p * 1.9 - vec2(time * 0.09, time * 0.06));
    float heat = smoothstep(0.25, 0.85, slow * 0.65 + fine * 0.35);

    return base * mix(vec3(0.62, 0.42, 0.34), vec3(1.55, 1.30, 0.95), heat);
}

void main() {
    vec4 color = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, TexelSize) : sampleNearest(Sampler0, texCoord0, TexelSize)) * vertexColor;

    if (color.a < AlphaCutout) {
        discard;
    }

    // Terrain has no normal attribute, the geometric one is exact for flat faces
    vec3 normal = normalize(cross(dFdx(relPos), dFdy(relPos)));
    if (dot(normal, relPos) > 0.0)
        normal = -normal;

    float shade = mix(vanillaShade(normal), 0.9, LuminaShadowColor.a);

    // Cross-shaped plants and other diagonal quads are not shaded by vanilla and look wrong with a hard terminator
    float axisAligned = step(0.9, max(max(abs(normal.x), abs(normal.y)), abs(normal.z)));
    shade = mix(1.0, shade, axisAligned);

    // Sunlight only where the sky reaches: caves and sealed rooms keep plain lightmap lighting
    float skyAccess = smoothstep(0.2, 0.7, skyLevel);
    float strength = LuminaLitColor.a * skyAccess;

    vec3 light = lightColor;
    float waterLit = 0.0;

    if (strength > 0.001) {
        float ndl = dot(normal, LuminaLightDir.xyz);
        float diffuse = mix(1.0, smoothstep(0.0, 0.3, ndl), axisAligned);

        float lit = diffuse;

        if (LuminaLightDir.w > 0.5 && diffuse > 0.0) {
            float shadow = sampleShadow(relPos, normal, ndl);
            float distFade = smoothstep(LuminaParams.z * 0.8, LuminaParams.z, length(relPos));
            lit *= mix(shadow, 1.0, distFade);
        }

        vec3 dayLight = mix(LuminaShadowColor.rgb, LuminaLitColor.rgb, lit);
        waterLit = lit;

        // The sun provides the direction, so the baked-looking face shading is mostly faded out
        float softShade = mix(shade, 1.0, 0.55);

        light *= mix(vec3(shade), dayLight * softShade, strength);
    }
    else {
        light *= shade;
    }

    bool isPlain = blockKind < 0.5;
    bool isWater = blockKind > 2.5 && blockKind < 3.5;
    bool isGlossy = blockKind > 3.5 && blockKind < 4.5;
    bool isLava = blockKind > 7.5 && blockKind < 8.5;

    if (isLava && LuminaReflect.z > 0.5) {
        color.rgb = shadeLava(color.rgb);
    }
    else {
        color.rgb *= light * LuminaParams.w;
    }

    if (isWater && LuminaSky.a > 0.5 && normal.y > 0.5) {
        color = shadeWater(color.rgb, light, skyAccess, waterLit * strength);
    }
    else if (isGlossy && LuminaReflect.x > 0.0 && AlphaCutout < 0.05) {
        // Ice and stained glass. Clear glass is cut out rather than blended, there is nothing to draw a reflection on
        float amount = fresnel(normal, 0.05) * LuminaReflect.x;
        color.rgb = mix(color.rgb, mirror(color.rgb, normal, skyAccess), amount);
        color.a = max(color.a, amount);
    }
    else if (isPlain && LuminaReflect.y > 0.01 && normal.y > 0.9) {
        // Rain: ground under open sky darkens, and patches of it fill with water that mirrors the surroundings
        float wetness = LuminaReflect.y * smoothstep(0.88, 0.98, skyLevel) * (1.0 - smoothstep(32.0, 56.0, sphericalVertexDistance));

        if (wetness > 0.01) {
            float puddle = smoothstep(0.50, 0.66, valueNoise((relPos.xz + LuminaCamera.xz) * 0.23));
            float amount = wetness * (0.06 + 0.94 * puddle) * fresnel(normal, 0.12);

            color.rgb *= mix(1.0, 0.8, wetness);
            color.rgb = mix(color.rgb, mirror(color.rgb, normal, skyAccess), amount);
        }
    }

    color = mix(FogColor * vec4(1, 1, 1, color.a), color, fadeFactor);

    float fog = max(fogValue(sphericalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd),
                    fogValue(cylindricalVertexDistance, FogRenderDistanceStart, FogRenderDistanceEnd));

    fragColor = vec4(mix(color.rgb, FogColor.rgb, fog * FogColor.a), color.a);
}
