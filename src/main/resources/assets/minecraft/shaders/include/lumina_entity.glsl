#version 330

layout(std140) uniform LuminaEntity {
    mat4 LuminaShadowMat;
    mat4 LuminaShadowMatFar;
    mat4 LuminaInvView;
    vec4 LuminaLightDir;
    vec4 LuminaLitColor;
    vec4 LuminaShadowColor;
    vec4 LuminaEntityParams;
};
