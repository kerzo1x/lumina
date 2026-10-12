layout (binding = 1) uniform FogUBO {
    vec4 FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float AlphaCutout;
    vec2 TexelSize;
    vec2 ScreenSize;
    int UseRgss;
};

layout (binding = 3) uniform LuminaUBO {
    // Camera-relative world space to the clip space of the near and far shadow cascades
    mat4 LuminaShadowMat;
    mat4 LuminaShadowMatFar;
    // x: world size of a near cascade texel, y: same for the far cascade
    vec4 LuminaShadowParams;
    // xyz: direction towards the sun or moon, w: 0 no shadow maps, 1 near cascade only, 2 both cascades
    vec4 LuminaLightDir;
    // rgb: multiplier for surfaces in direct light, a: strength of the directional light (0 = vanilla look)
    vec4 LuminaLitColor;
    // rgb: multiplier for surfaces in shadow, a: 1 in dimensions where every face is shaded alike (Nether)
    vec4 LuminaShadowColor;
    // x: warmth of block light, y: shadow softness, z: shadow distance, w: ambient brightness
    vec4 LuminaParams;
    // xyz: camera position wrapped to 1024 blocks, w: time in seconds
    vec4 LuminaCamera;
    // rgb: sky colour overhead, a: 1 when water effects are enabled
    vec4 LuminaSky;
    // x: amount of wind sway (0 = off), y: gustiness
    vec4 LuminaWave;
    // x: reflections on glossy blocks (0 = off), y: wetness of the ground in rain, z: 1 for animated lava, w: water wave height
    vec4 LuminaReflect;
};
