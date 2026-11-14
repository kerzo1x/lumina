// The cloud layer, shared by the sky and by the cloud shadows on the ground so the shadows match the clouds overhead.
// assets/minecraft/shaders/include/lumina_effects.glsl carries a copy for the entity shader: keep the two in step.

const float CLOUD_HEIGHT = 192.0;
// Cloud layer coordinates (blocks) to noise coordinates
const float CLOUD_SCALE = 0.0024;

float cloudHash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float cloudNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);

    return mix(mix(cloudHash(i), cloudHash(i + vec2(1.0, 0.0)), f.x),
               mix(cloudHash(i + vec2(0.0, 1.0)), cloudHash(i + vec2(1.0, 1.0)), f.x), f.y);
}

float cloudFbm(vec2 p, int octaves) {
    float sum = 0.0;
    float amplitude = 0.5;

    for (int i = 0; i < octaves; ++i) {
        sum += amplitude * cloudNoise(p);
        p = p * 2.03 + vec2(17.1, 9.7);
        amplitude *= 0.5;
    }

    return sum;
}

// Shape of the cloud cover at a point of the layer (in noise coordinates), roughly 0..1 before the coverage threshold
float cloudShape(vec2 p) {
    // fbm tops out a little below 1, hence the division
    return (cloudFbm(p, 5) * 0.72 + cloudFbm(p * 2.9 + 31.0, 5) * 0.28) / 0.97;
}

// The same shape without its finest octaves, for shadows: those only fray the edges of a cloud, which a shadow
// on the ground blurs away anyway. The dropped octaves are replaced by their average so the cover stays the same.
float cloudShapeCoarse(vec2 p) {
    return ((cloudFbm(p, 3) + 0.047) * 0.72 + (cloudFbm(p * 2.9 + 31.0, 2) + 0.109) * 0.28) / 0.97;
}
