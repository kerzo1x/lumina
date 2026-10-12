#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in float lineDistance;
in float lineHalfWidth;

out vec4 fragColor;

void main() {
    vec4 color = vertexColor * ColorModulator;

    // Lumina: full opacity inside the line, fading to nothing over the one pixel margin around it
    float coverage = clamp(lineHalfWidth + 0.5 - abs(lineDistance), 0.0, 1.0);
    if (coverage <= 0.01) {
        discard;
    }
    color.a *= coverage;

    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
