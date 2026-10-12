#version 460

// Screen-space ray tracing against the depth buffer, at half resolution.
// Red: ambient occlusion (1 = open, 0 = buried in a corner).
// Green: contact shadow (1 = lit, 0 = something close by stands between this point and the light).

layout (binding = 0) uniform AoUBO {
    // x: 1 / projection[0][0], y: 1 / projection[1][1], z: projection[0][0], w: projection[1][1]
    vec4 LuminaProj;
    // xyz: direction to the light in view space, w: 1 when contact shadows are wanted
    vec4 LuminaLightView;
    // x: occlusion radius in blocks (0 = occlusion off), y: contact shadow ray length, z: furthest distance treated
    vec4 LuminaAoParams;
};

// Distance from the camera, see copy_depth.fsh
layout (binding = 1) uniform sampler2D Sampler0;

layout (location = 0) in vec2 texCoord;

layout (location = 0) out vec4 fragColor;

const float SKY_DISTANCE = 50000.0;

// Sixteen evenly spread values. A 4x4 box blur afterwards averages exactly one of each.
const float PATTERN[16] = float[](0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0, 3.0, 11.0, 1.0, 9.0, 15.0, 7.0, 13.0, 5.0);

// Points in the hemisphere around +Z, denser near the centre
const vec3 KERNEL[8] = vec3[](
    vec3( 0.20,  0.07, 0.12), vec3(-0.18,  0.24, 0.20), vec3( 0.08, -0.36, 0.26), vec3(-0.42, -0.15, 0.30),
    vec3( 0.48,  0.28, 0.38), vec3(-0.25,  0.58, 0.45), vec3( 0.52, -0.52, 0.42), vec3(-0.66, -0.40, 0.55)
);

vec3 viewPosition(vec2 uv, float depth) {
    vec2 ndc = vec2(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0);
    return vec3(ndc * LuminaProj.xy * depth, -depth);
}

vec2 screenPosition(vec3 view) {
    vec2 ndc = view.xy * LuminaProj.zw / -view.z;
    return vec2(ndc.x * 0.5 + 0.5, 0.5 - ndc.y * 0.5);
}

// Depth is always read from one whole pixel of the full-size buffer and positions are rebuilt from the centre of
// that same pixel. Filtering, or rebuilding from a point between pixels, puts points off their surface by a
// fraction of the depth step between rows, which on ground seen at a shallow angle is enough to shade it in bands.
float depthAt(ivec2 pixel) {
    return texelFetch(Sampler0, clamp(pixel, ivec2(0), textureSize(Sampler0, 0) - 1), 0).r;
}

float depthAt(vec2 uv) {
    return depthAt(ivec2(uv * vec2(textureSize(Sampler0, 0))));
}

void main() {
    vec2 size = vec2(textureSize(Sampler0, 0));
    ivec2 pixel = ivec2(gl_FragCoord.xy) * 2;
    vec2 uv = (vec2(pixel) + 0.5) / size;
    float depth = depthAt(pixel);

    if (depth >= SKY_DISTANCE || depth > LuminaAoParams.z) {
        fragColor = vec4(1.0);
        return;
    }

    vec2 texel = 1.0 / size;
    vec3 position = viewPosition(uv, depth);

    // Surface normal from neighbouring pixels, on each axis taking the neighbour on the same surface
    float left = depthAt(pixel - ivec2(1, 0));
    float right = depthAt(pixel + ivec2(1, 0));
    float above = depthAt(pixel - ivec2(0, 1));
    float below = depthAt(pixel + ivec2(0, 1));

    vec3 dx = abs(left - depth) < abs(right - depth)
            ? position - viewPosition(uv - vec2(texel.x, 0.0), left)
            : viewPosition(uv + vec2(texel.x, 0.0), right) - position;
    vec3 dy = abs(above - depth) < abs(below - depth)
            ? position - viewPosition(uv - vec2(0.0, texel.y), above)
            : viewPosition(uv + vec2(0.0, texel.y), below) - position;

    vec3 normal = normalize(cross(dx, dy));
    if (dot(normal, position) > 0.0)
        normal = -normal;

    ivec2 cell = ivec2(gl_FragCoord.xy) & 3;
    float pattern = PATTERN[cell.x + cell.y * 4] / 16.0;

    // How far depth changes from one pixel to the next here: nothing smaller than that can be told apart
    float depthStep = max(abs(dx.z), abs(dy.z));

    float occlusion = 0.0;
    float radius = LuminaAoParams.x;

    if (radius > 0.0) {
        // Kernel turned by a different angle on every pixel of the 4x4 pattern
        float angle = pattern * 6.2831853;
        vec3 tangent = normalize(cross(normal, abs(normal.z) < 0.9 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0)));
        tangent = tangent * cos(angle) + cross(normal, tangent) * sin(angle);
        vec3 bitangent = cross(normal, tangent);

        for (int i = 0; i < 8; ++i) {
            vec3 probe = position + (tangent * KERNEL[i].x + bitangent * KERNEL[i].y + normal * KERNEL[i].z) * radius;
            float sceneDepth = depthAt(screenPosition(probe));

            // Something in front of the probe hides it, unless that something is far away from this surface
            float nearby = smoothstep(0.0, 1.0, radius / max(abs(depth - sceneDepth), 1.0e-4));
            occlusion += step(sceneDepth, -probe.z - 0.04 - depthStep) * nearby;
        }

        occlusion /= 8.0;
    }

    float contact = 0.0;
    vec3 lightDir = LuminaLightView.xyz;

    if (LuminaLightView.w > 0.5 && depth < 56.0 && dot(normal, lightDir) > 0.05) {
        // Short march towards the light: catches small things the shadow map is too coarse for
        float rayLength = LuminaAoParams.y * (1.0 + depth * 0.03);
        vec3 origin = position + normal * (0.02 + depth * 0.002);

        for (int i = 0; i < 8; ++i) {
            float travelled = rayLength * (float(i) + 0.4 + pattern) / 8.0;
            vec3 probe = origin + lightDir * travelled;
            vec2 probeUv = screenPosition(probe);

            if (probeUv.x < 0.0 || probeUv.x > 1.0 || probeUv.y < 0.0 || probeUv.y > 1.0)
                break;

            float behind = -probe.z - depthAt(probeUv);

            if (behind > 0.03 + depthStep * 1.5 && behind < 0.4 + depthStep * 1.5) {
                contact = 1.0 - 0.5 * travelled / rayLength;
                break;
            }
        }

        contact *= 1.0 - smoothstep(36.0, 56.0, depth);
    }

    fragColor = vec4(1.0 - occlusion, 1.0 - contact, 0.0, 1.0);
}
