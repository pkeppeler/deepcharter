#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

// Shadows/Highlights: rgb tint, a = strength. Params: x saturation, y vignette, z contrast, w green-to-olive amount.
// Lift: rgb added to every pixel before the contrast (a washed or murky floor).
layout(std140) uniform GradeConfig {
    vec4 Shadows;
    vec4 Highlights;
    vec4 Params;
    vec4 Lift;
};

layout(location = 0) out vec4 fragColor;

void main() {
    vec3 c = texture(InSampler, texCoord).rgb;
    float green = clamp((c.g - max(c.r, c.b)) * 4.0, 0.0, 1.0) * Params.w;
    c = mix(c, vec3(c.g * 0.85, c.g * 0.68, c.g * 0.42), green);
    c += Lift.rgb;
    float luma = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(vec3(luma), c, Params.x);
    c = (c - 0.5) * Params.z + 0.5;
    float lo = 1.0 - smoothstep(0.0, 0.55, luma);
    float hi = smoothstep(0.45, 1.0, luma);
    c = mix(c, c * Shadows.rgb * 3.0, lo * Shadows.a);
    c = mix(c, c * Highlights.rgb * 1.2, hi * Highlights.a);
    float d = distance(texCoord, vec2(0.5));
    c *= 1.0 - Params.y * smoothstep(0.35, 0.8, d);
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
