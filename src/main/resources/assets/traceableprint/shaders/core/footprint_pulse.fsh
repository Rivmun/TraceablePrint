// 分支内的空行占位必须写 "//"，不能写 "//#"（会被剥成非法的裸 "#" 行），原因见 footprint_pulse.vsh 顶部。
//? if < 26.3 {
//#version 330
//
//#moj_import <minecraft:dynamictransforms.glsl>
//
//in vec4 vertexColor;
//in vec2 texCoord0;
//
//out vec4 fragColor;
//? } else {
#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;

layout(location = 0) out vec4 fragColor;
//? }

uniform sampler2D Sampler0;

// 高亮脉冲：在 footprint.png 原色与纯白之间往复闪烁。
// 脉冲强度由顶点色 alpha 承载（CPU 端按全局游戏时间算 sin 波写入），不采样 lightmap → 不受世界光照。
// 穿墙由管线的关深度状态保证（见 FootprintRenderTypes）。
void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    if (tex.a < 0.1) {
        discard;
    }

    float pulse = clamp(vertexColor.a, 0.0, 1.0);
    vec3 rgb = mix(tex.rgb, vec3(1.0), pulse);

    fragColor = vec4(rgb * ColorModulator.rgb, tex.a * ColorModulator.a);
}
