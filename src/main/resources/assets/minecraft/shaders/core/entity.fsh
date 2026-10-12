#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:lumina_entity.glsl>

uniform sampler2D Sampler0;
uniform sampler2D LuminaShadowNear;
uniform sampler2D LuminaShadowFar;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
#ifdef PER_FACE_LIGHTING
in vec4 vertexPerFaceColorBack;
in vec4 vertexPerFaceColorFront;
#else
in vec4 vertexColor;
#endif
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;
in vec3 luminaRelPos;
in float luminaSkyLight;

out vec4 fragColor;

// Lumina: depth comparison with bilinear weights, done by hand because this shader only gets plain samplers
float luminaShadowTap(sampler2D shadowMap, vec2 uv, float depth) {
    vec2 size = vec2(textureSize(shadowMap, 0));
    vec2 texel = uv * size - 0.5;
    vec2 base = (floor(texel) + 0.5) / size;
    vec2 weight = fract(texel);
    vec2 texelStep = 1.0 / size;

    float s00 = step(depth, textureLod(shadowMap, base, 0.0).r);
    float s10 = step(depth, textureLod(shadowMap, base + vec2(texelStep.x, 0.0), 0.0).r);
    float s01 = step(depth, textureLod(shadowMap, base + vec2(0.0, texelStep.y), 0.0).r);
    float s11 = step(depth, textureLod(shadowMap, base + texelStep, 0.0).r);

    return mix(mix(s00, s10, weight.x), mix(s01, s11, weight.x), weight.y);
}

float luminaShadow(vec3 pos) {
    // Creatures are in the shadow map themselves (as rough boxes). Looking the shadow up a little way towards
    // the light keeps a creature from shading its own sunlit side.
    vec3 lookup = pos + LuminaLightDir.xyz * 0.7;

    vec3 nearClip = (LuminaShadowMat * vec4(lookup, 1.0)).xyz;
    vec3 nearUv = vec3(nearClip.xy * 0.5 + 0.5, nearClip.z - 0.0001);

    if (max(abs(nearClip.x), abs(nearClip.y)) < 0.94) {
        vec2 spread = 1.5 / vec2(textureSize(LuminaShadowNear, 0));
        float sum = luminaShadowTap(LuminaShadowNear, nearUv.xy, nearUv.z);
        sum += luminaShadowTap(LuminaShadowNear, nearUv.xy + vec2(spread.x, spread.y), nearUv.z);
        sum += luminaShadowTap(LuminaShadowNear, nearUv.xy + vec2(-spread.x, spread.y), nearUv.z);
        sum += luminaShadowTap(LuminaShadowNear, nearUv.xy + vec2(spread.x, -spread.y), nearUv.z);
        sum += luminaShadowTap(LuminaShadowNear, nearUv.xy + vec2(-spread.x, -spread.y), nearUv.z);
        return sum * 0.2;
    }

    if (LuminaLightDir.w > 1.5) {
        vec3 farClip = (LuminaShadowMatFar * vec4(lookup, 1.0)).xyz;
        vec3 farUv = vec3(farClip.xy * 0.5 + 0.5, farClip.z - 0.0002);

        if (farUv.x > 0.0 && farUv.x < 1.0 && farUv.y > 0.0 && farUv.y < 1.0 && farUv.z < 1.0)
            return luminaShadowTap(LuminaShadowFar, farUv.xy, farUv.z);
    }

    return 1.0;
}

// Lumina: the same warm-lit / cool-shadow multiplier the terrain uses. 1.0 when the pipeline is off, in menus,
// and where the sky does not reach.
vec3 luminaLight() {
    float skyAccess = smoothstep(0.2, 0.7, luminaSkyLight);
    float strength = LuminaLitColor.a * skyAccess;

    if (LuminaEntityParams.x < 0.5 || strength <= 0.001)
        return vec3(1.0);

    float lit = LuminaLightDir.w > 0.5 ? luminaShadow(luminaRelPos) : 1.0;

    return mix(vec3(1.0), mix(LuminaShadowColor.rgb, LuminaLitColor.rgb, lit), strength);
}

void main() {
    vec4 color = texture(Sampler0, texCoord0);
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
#ifdef PER_FACE_LIGHTING
    color *= (gl_FrontFacing ? vertexPerFaceColorFront : vertexPerFaceColorBack) * ColorModulator;
#else
    color *= vertexColor * ColorModulator;
#endif
#ifndef NO_OVERLAY
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
#endif
#ifndef EMISSIVE
    color *= lightMapColor;
    color.rgb *= luminaLight();
#endif
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
