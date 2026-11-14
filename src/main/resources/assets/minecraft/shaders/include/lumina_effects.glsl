#version 330

// Lumina: haze and cloud shadows for the entity shader, the same as the terrain applies.
// The cloud functions are a copy of assets/lumina/shaders/include/clouds.glsl: keep the two in step.
// Expects lumina_entity.glsl and fog.glsl to be imported first.

const float LUMINA_CLOUD_HEIGHT = 192.0;
const float LUMINA_CLOUD_SCALE = 0.0024;

float luminaCloudHash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float luminaCloudNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);

    return mix(mix(luminaCloudHash(i), luminaCloudHash(i + vec2(1.0, 0.0)), f.x),
               mix(luminaCloudHash(i + vec2(0.0, 1.0)), luminaCloudHash(i + vec2(1.0, 1.0)), f.x), f.y);
}

float luminaCloudFbm(vec2 p, int octaves) {
    float sum = 0.0;
    float amplitude = 0.5;

    for (int i = 0; i < octaves; ++i) {
        sum += amplitude * luminaCloudNoise(p);
        p = p * 2.03 + vec2(17.1, 9.7);
        amplitude *= 0.5;
    }

    return sum;
}

// Share of the direct light that gets through the clouds to this camera-relative position
float luminaCloudLight(vec3 rel) {
    if (LuminaCloud.w <= 0.0 || LuminaLightDir.y < 0.05)
        return 1.0;

    float toLayer = (LUMINA_CLOUD_HEIGHT - (LuminaHaze.z + rel.y)) / LuminaLightDir.y;
    if (toLayer <= 0.0)
        return 1.0;

    vec2 p = (rel.xz + LuminaLightDir.xz * toLayer + LuminaCloud.xy) * LUMINA_CLOUD_SCALE;
    float shape = ((luminaCloudFbm(p, 3) + 0.047) * 0.72 + (luminaCloudFbm(p * 2.9 + 31.0, 2) + 0.109) * 0.28) / 0.97;

    return 1.0 - smoothstep(LuminaCloud.z, LuminaCloud.z + 0.13, shape) * LuminaCloud.w;
}

// Distance haze that gathers in low ground. skyAccess keeps it out of caves and rooms.
vec3 luminaHaze(vec3 color, vec3 rel, float skyAccess) {
    if (LuminaHaze.x <= 0.0 || skyAccess <= 0.0)
        return color;

    float dist = length(rel);
    float k = LuminaHaze.y;
    float height = max(LuminaHaze.z - 62.0, -8.0);
    float dy = rel.y * k;

    // Density exp(-k * height above sea level), integrated along the ray from the camera
    float along = abs(dy) > 1.0e-3 ? (1.0 - exp(-dy)) / dy : 1.0 - 0.5 * dy;
    float amount = (1.0 - exp(-LuminaHaze.x * exp(-k * height) * dist * along)) * skyAccess;

    vec3 dir = rel / max(dist, 1.0e-3);
    float glow = pow(max(dot(dir, LuminaLightDir.xyz), 0.0), 8.0) * LuminaHaze.w;

    return mix(color, FogColor.rgb + LuminaLitColor.rgb * glow * 0.35, amount);
}
