// Wind sway for foliage. Expects LuminaCamera (xyz: wrapped camera position, w: time) and
// LuminaWave (x: overall amount, y: gustiness) to be declared by the including shader.

const uint KIND_LEAVES = 1u;
const uint KIND_PLANT = 2u;
const uint KIND_WATER = 3u;

// flags: low four bits of the packed block light, see BlockFlags.java
vec3 windOffset(uint flags, vec3 relativePos) {
    uint kind = flags & 3u;

    if (LuminaWave.x <= 0.0 || (kind != KIND_LEAVES && kind != KIND_PLANT))
        return vec3(0.0);

    // Too far to notice, and the far shadow cascade is not redrawn often enough to follow
    float amount = LuminaWave.x * (1.0 - smoothstep(32.0, 48.0, length(relativePos)));
    if (amount <= 0.0)
        return vec3(0.0);

    // Driven by world position only, so vertices shared between neighbouring blocks move together
    vec3 p = relativePos + LuminaCamera.xyz;
    float time = LuminaCamera.w;
    float gust = 0.65 + 0.35 * sin(time * 0.45 + p.x * 0.06 + p.z * 0.04) * LuminaWave.y;

    if (kind == KIND_LEAVES) {
        vec3 sway = vec3(sin(time * 1.7 + p.x * 1.3 + p.y * 0.7 + p.z * 0.9),
                         sin(time * 1.3 + p.x * 0.8 + p.z * 1.1) * 0.5,
                         cos(time * 1.5 + p.x * 0.9 - p.y * 0.6 + p.z * 1.2));
        return sway * 0.035 * gust * amount;
    }

    // Plants bend from the root: weight is 0 there and 1 at the tip
    float weight = float((flags >> 2) & 3u) * 0.5;
    vec2 sway = vec2(sin(time * 2.1 + p.x * 0.9 + p.z * 1.4), cos(time * 1.7 + p.x * 1.1 - p.z * 0.8));
    return vec3(sway.x, 0.0, sway.y) * 0.11 * weight * gust * amount;
}
