package com.rm.interchange.map.gl

import com.rm.interchange.map.MapOverlays.CONDITION
import com.rm.interchange.map.MapOverlays.GRID
import com.rm.interchange.map.MapOverlays.HEIGHTS
import com.rm.interchange.map.MapOverlays.SNOW

/** A colour written RRGGBB as a GLSL vec3. */
private fun rgb(c: Int): String {
    fun ch(shift: Int) = (((c shr shift) and 255) / 255f).toString()
    return "vec3(${ch(16)}, ${ch(8)}, ${ch(0)})"
}

/**
 * GLSL for a sprite's vertex shader: spriteAt places cell c of the sheet bound (cell table uCells, uCellRows rows a
 * level, CELLS_ACROSS cells a row) at
 * level uLevel, scaled to source level pixels by uToSource, and snowCover reads the snow on a world tile from
 * uWeather's fourth byte as TileRecorder.snowCover does. Needs uPage and uWeather declared before it.
 */
internal val SPRITE_PLACE = """
uniform highp isampler2D uCells;
uniform int uCellRows;
uniform int uLevel;
uniform float uToSource;
float snowCover(int t) {
    if (t < 0) return 0.0;
    uint d = texelFetch(uWeather, ivec2(t & 0xffff, t >> 16), 0).a;
    if (d == 0u) return 0.0;
    if (d < 3u) return 90.0;
    if (d < 10u) return 170.0;
    if (d < 30u) return 230.0;
    return 256.0;
}
// Cell c anchored at a, in level 0 pixels rounded down, at corner o of its quad: where it lands, with its sheet place
// and layer in uv and the middles of its edge texels in edge, so nothing next to it on the sheet bleeds in.
vec2 spriteAt(int c, ivec2 a, vec2 o, out vec3 uv, out vec4 edge) {
    ivec2 t = ivec2(c % $CELLS_ACROSS * 2, uLevel * uCellRows + c / $CELLS_ACROSS);
    ivec4 p = texelFetch(uCells, t, 0);
    ivec4 q = texelFetch(uCells, t + ivec2(1, 0), 0);
    vec2 size = vec2(float(p.w), float(q.x));
    vec2 at = vec2(float(p.x), float(p.y));
    uv = vec3((at + o * size) / uPage, float(p.z));
    edge = vec4((at + 0.5) / uPage, (at + size - 0.5) / uPage);
    vec2 lt = vec2(float((a.x >> uLevel) + q.y), float((a.y >> uLevel) + q.z));
    return (lt + o * size) * uToSource;
}
"""

/**
 * GLSL for a static sprite's fragment shader: staticTexel samples uSheet (premultiplied) inside the sprite's edges,
 * with snow cover [0, 256] laid on what faces the sky by uUp (how much it faces up, 0 to 3 as 0 to 1), as blitOn does.
 */
internal val STATIC_TEXEL = """
uniform mediump sampler2DArray uUp;
const vec3 SNOW_TOP = ${rgb(0xf4f7fa)};
vec4 staticTexel(highp vec3 uv, highp vec4 edge, float snow) {
    highp vec3 at = vec3(clamp(uv.xy, edge.xy, edge.zw), uv.z);
    vec4 c = texture(uSheet, at);
    if (snow > 0.0) {
        float up = texture(uUp, at).r * 3.0;
        if (up > 0.0) {
            // A little greyer where it's steeper.
            vec3 white = SNOW_TOP - (3.0 - up) * (10.0 / 255.0);
            c.rgb = mix(c.rgb, white * c.a, snow * up / 768.0);
        }
    }
    return c;
}
"""

/**
 * The first pass's vertex shader (GLSL ES 3.00): each instance is a [GroundRecord] or, with type [GroundRecord.SPRITE],
 * a sprite (cell and snow tile in aA, anchor in aB, placed by [SPRITE_PLACE]). A tile's top is two triangles split
 * along the diagonal the tile recorder splits it by; an earth face is a quad down to height -2. The weather comes from [GroundWeather] by world corner.
 */
internal val GROUND_VS = """#version 300 es
precision highp float;
precision highp int;
layout(location = 0) in uvec4 aA;
layout(location = 1) in ivec2 aB;
layout(location = 2) in vec4 aC;
layout(location = 3) in vec4 aD;
uniform vec4 uXf;
uniform vec2 uPage;
// Map width, height and the view's turn.
uniform ivec3 uMap;
uniform highp usampler2D uWeather;
// Overlays as MapOverlays bits, and the track wear by world tile for the condition one.
uniform int uOverlay;
uniform highp usampler2D uWear;
$SPRITE_PLACE
flat out uvec4 vA;
flat out ivec2 vB;
flat out vec4 vRel;
flat out vec4 vLight;
flat out vec4 vSnow;
flat out vec4 vIce;
flat out vec4 vUp;
flat out float vMud;
flat out vec4 vEdge;
flat out float vSpriteSnow;
flat out int vWear;
out vec3 vUv;

const vec2 OFF[4] = vec2[4](vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(1.0, 1.0), vec2(0.0, 1.0));
const int RL[4] = int[4](0, 1, 3, 2);
const int TB[4] = int[4](1, 0, 2, 3);

ivec2 worldCorner(ivec2 c) {
    if (uMap.z == 0) return c;
    if (uMap.z == 1) return ivec2(c.y, uMap.y - c.x);
    if (uMap.z == 2) return ivec2(uMap.x - c.x, uMap.y - c.y);
    return ivec2(uMap.x - c.y, c.x);
}

ivec2 worldTile(ivec2 v) {
    if (uMap.z == 0) return v;
    if (uMap.z == 1) return ivec2(v.y, uMap.y - 1 - v.x);
    if (uMap.z == 2) return ivec2(uMap.x - 1 - v.x, uMap.y - 1 - v.y);
    return ivec2(uMap.x - 1 - v.y, v.x);
}

// Source level pixels from view point 0, 0 at height 0: a half tile 64 wide and 32 high, a step 16.
vec2 place(vec2 v, float h) {
    return vec2((v.x - v.y) * 64.0, (v.x + v.y) * 32.0 - h * 16.0);
}

void main() {
    vA = aA;
    vB = aB;
    vRel = aC;
    vLight = aD;
    vSnow = vec4(0.0);
    vIce = vec4(0.0);
    vUp = vec4(0.0);
    vMud = 0.0;
    vEdge = vec4(0.0);
    vSpriteSnow = 0.0;
    vWear = 0;
    uint type = (aA.z >> 20) & 3u;
    int id = gl_VertexID;
    vec2 p;
    if (type == 3u) {
        vec2 o = vec2(float(id & 1), float(id >> 1));
        p = spriteAt(int(aA.x), aB, o, vUv, vEdge);
        vSpriteSnow = snowCover(aA.y == 0xffffffffu ? -1 : int(aA.y));
    } else {
        ivec2 tile = ivec2(int(aA.x & 0xffffu), int(aA.x >> 16));
        vec4 h = vec4(float(aA.y & 255u), float((aA.y >> 8) & 255u), float((aA.y >> 16) & 255u), float(aA.y >> 24));
        if (type == 0u) {
            // A strip whose middle edge is the split.
            int c = h.y == h.w ? RL[id] : TB[id];
            p = place(vec2(tile) + OFF[c], h[c]);
            vUv = vec3(OFF[c], 0.0);
            for (int k = 0; k < 4; k++) {
                uvec4 w = texelFetch(uWeather, worldCorner(tile + ivec2(OFF[k])), 0);
                vSnow[k] = float(w.r) * 0.25;
                vIce[k] = float(w.g) * 0.25;
            }
            uint kind = aA.z & 15u;
            bool weather = ((aA.z >> 22) & 1u) != 0u;
            if (weather && kind != 7u && kind != 6u) vMud = float(texelFetch(uWeather, worldTile(tile), 0).b) / 255.0 * 0.45;
            float low = min(min(h.x, h.y), min(h.z, h.w));
            float rise = max(max(h.x, h.y), max(h.z, h.w)) - low;
            if (rise > 0.0) vUp = (h - low) / rise;
#ifdef OVERLAYS
            if ((uOverlay & $CONDITION) != 0) vWear = int(texelFetch(uWear, worldTile(tile), 0).r);
#endif
        } else {
            // Right face from the right corner to the bottom, left from the left; tops, then down to -2.
            int c = (id & 1) == 0 ? (type == 1u ? 1 : 3) : 2;
            p = place(vec2(tile) + OFF[c], (id >> 1) == 0 ? h[c] : -2.0);
            vUv = vec3(0.0);
        }
    }
    gl_Position = vec4(p * uXf.xy + uXf.zw, 0.0, 1.0);
}
"""

/**
 * The first pass's fragment shader: a sprite from the static sheet, an earth face, or a tile's ground worked out as
 * [GroundRecord] describes it, texel by texel, from the ground sheet ([uGround], the kinds then the noise in layer
 * 12's blue) laid in view coordinates every 4 tiles.
 */
internal val GROUND_FS = """#version 300 es
precision highp float;
precision highp int;
uniform mediump sampler2DArray uSheet;
uniform mediump sampler2DArray uGround;
// The same ground sheet sampled nearest, for the noise, so snow and bare patches keep ragged edges.
uniform mediump sampler2DArray uRag;
uniform highp sampler2D uLegs;
// Taxi legs of each airport kind in uLegs' rows, and the grass colour mixed in for each kind at this time of year.
uniform int uLegCount[6];
uniform vec4 uGrade[8];
uniform int uOverlay;
uniform int uLevel;
flat in uvec4 vA;
flat in ivec2 vB;
flat in vec4 vRel;
flat in vec4 vLight;
flat in vec4 vSnow;
flat in vec4 vIce;
flat in vec4 vUp;
flat in float vMud;
flat in vec4 vEdge;
flat in float vSpriteSnow;
flat in int vWear;
in vec3 vUv;
out vec4 frag;
$STATIC_TEXEL

const vec3 FOAM = ${rgb(0xe8f0f4)};
const vec3 ICE = ${rgb(0xd4e2ea)};
const vec3 ICE_DARK = ${rgb(0xa8bccb)};
const vec3 MUD = ${rgb(0x5a4630)};
const vec3 PACKED_SNOW = ${rgb(0x9c9a94)};
const vec3 STONE = ${rgb(0x8e8a82)};
const vec3 CHARCOAL = ${rgb(0x2e2a26)};
const vec3 EARTH_LIGHT = ${rgb(0x7a6248)};
const vec3 EARTH_DARK = ${rgb(0x5a4634)};
const vec3 GRAVEL_GREY = ${rgb(0x9a948a)};
const vec3 EDGE_WHITE = ${rgb(0xe8e8e2)};
const vec3 TRAM_RAIL = ${rgb(0x4a4846)};
const vec3 ROAD_ARROW = ${rgb(0xeaeae4)};
const vec3 ROAD_LINE = ${rgb(0xd6c46a)};
const vec3 ASPHALT = ${rgb(0x4a4c4e)};
const vec3 STRIP_GRAVEL = ${rgb(0xa8987c)};
const vec3 APRON = ${rgb(0xb4b0a8)};
const vec3 APRON_JOINT = ${rgb(0x96928a)};
const vec3 MARKING = ${rgb(0xeeeeea)};
const vec3 TAXI_LINE = ${rgb(0xe8c030)};
const float PI = 3.14159265;
const vec3 GRID_LIGHT = ${rgb(0xf2efe6)};
const vec3 WEAR[3] = vec3[3](${rgb(0xd03a2a)}, ${rgb(0xe8b030)}, ${rgb(0x3aa048)});
// The snow overlay's bands: under 10 cm, under 30, under 60, deeper; then ice on the water.
const vec3 SNOW_BAND[4] = vec3[4](${rgb(0x9fd0f0)}, ${rgb(0x5a9ae0)}, ${rgb(0x3a52c8)}, ${rgb(0x6a2ab0)});
const vec3 ICE_BAND = ${rgb(0x40e0e0)};
// The heights overlay's bands, each 2 steps, from sea level up.
const vec3 HEIGHT[12] = vec3[12](
    ${rgb(0x2f7a3a)}, ${rgb(0x4f9440)}, ${rgb(0x7fac4a)}, ${rgb(0xb0bc58)}, ${rgb(0xd4c062)}, ${rgb(0xd8a456)},
    ${rgb(0xc8844a)}, ${rgb(0xa86a44)}, ${rgb(0x8a5a46)}, ${rgb(0x9a8478)}, ${rgb(0xc8c0bc)}, ${rgb(0xf2f2f6)}
);

vec2 tc;
vec2 gx;
vec2 gy;

vec3 K(int kind) {
    return textureGrad(uGround, vec3(tc, float(kind)), gx, gy).rgb;
}

float ragAt(vec2 at) {
    return textureGrad(uRag, vec3(at, 12.0), gx, gy).b;
}

// Bits: the view edges XM 1, XP 2, YM 4, YP 8.
bool cornerOf(int bits, out vec2 c) {
    int x = bits & 3;
    int y = bits & 12;
    c = vec2(x == 1 ? 0.0 : 1.0, y == 4 ? 0.0 : 1.0);
    return (x == 1 || x == 2) && (y == 4 || y == 8);
}

float roadDistance(float u, float v, int bits) {
    vec2 c;
    if (cornerOf(bits, c)) return abs(length(vec2(u, v) - c) - 0.5);
    float du = abs(u - 0.5);
    float dv = abs(v - 0.5);
    float d = max(du, dv);
    if ((u < 0.5 && (bits & 1) != 0) || (u >= 0.5 && (bits & 2) != 0)) d = min(d, dv);
    if ((v < 0.5 && (bits & 4) != 0) || (v >= 0.5 && (bits & 8) != 0)) d = min(d, du);
    return d;
}

bool tramRail(float u, float v, int bits) {
    vec2 c;
    if (cornerOf(bits, c)) return abs(roadDistance(u, v, bits) - 0.06) < 0.008;
    float du = abs(u - 0.5);
    float dv = abs(v - 0.5);
    bool alongU = (u < 0.5 && (bits & 1) != 0) || (u >= 0.5 && (bits & 2) != 0);
    bool alongV = (v < 0.5 && (bits & 4) != 0) || (v >= 0.5 && (bits & 8) != 0);
    return (alongU && abs(dv - 0.06) < 0.008) || (alongV && abs(du - 0.06) < 0.008);
}

bool oneWayArrow(float u, float v, int edge) {
    vec2 a;
    if (edge == 1) a = vec2(u - 0.5, v - 0.5);
    else if (edge == 0) a = vec2(0.5 - u, v - 0.5);
    else if (edge == 3) a = vec2(v - 0.5, u - 0.5);
    else a = vec2(0.5 - v, u - 0.5);
    if (a.x < -0.18 || a.x > 0.18) return false;
    return a.x < 0.04 ? abs(a.y) < 0.025 : abs(a.y) < (0.18 - a.x) * 0.6;
}

// Road bits: edges, paved 16, downtown 32, gravel 64, highway 128, tram 256, one way 512 with its edge at 10, town 4096.
// part: 0 the ground came back, 1 mixed with it, 2 paving.
vec3 roadTexel(float u, float v, int bits, vec3 ground, out int part) {
    part = 2;
    bool paved = (bits & 16) != 0;
    bool highway = (bits & 128) != 0;
    float d = roadDistance(u, v, bits);
    float half_ = highway ? 0.3 : 0.2;
    if (d < half_) {
        bool sx = (bits & 15) == 3;
        bool sy = (bits & 15) == 12;
        if ((bits & 256) != 0 && tramRail(u, v, bits)) return TRAM_RAIL;
        if ((bits & 512) != 0 && oneWayArrow(u, v, (bits >> 10) & 3)) return ROAD_ARROW;
        if (!paved) {
            vec3 dirt = K(9);
            return (bits & 64) != 0 ? mix(dirt, GRAVEL_GREY, 0.45) : dirt;
        }
        if (highway && d > half_ - 0.024 && d < half_ - 0.012) return EDGE_WHITE;
        if ((sx && abs(v - 0.5) < 0.012 && fract(u * 5.0) < 0.55) || (sy && abs(u - 0.5) < 0.012 && fract(v * 5.0) < 0.55)) return ROAD_LINE;
        vec2 c;
        if (cornerOf(bits, c) && d < 0.012 && fract(atan(abs(v - c.y), abs(u - c.x)) / (PI / 2.0) * 4.0) < 0.55) return ROAD_LINE;
        return K(10);
    }
    if (!paved || (bits & 4096) == 0) {
        if (paved && d < half_ + 0.05) {
            part = 1;
            return mix(ground, GRAVEL_GREY, 0.5);
        }
        part = 0;
        return ground;
    }
    vec3 walk = K(11);
    if (d < 0.215) return walk * (190.0 / 256.0);
    if ((bits & 32) != 0) return walk;
    if (d < 0.33) { part = 0; return ground; }
    if (d < 0.47) return walk;
    part = 0;
    return ground;
}

float toward(int side, float u, float v) {
    if (side == 0) return 1.0 - v;
    if (side == 1) return u;
    if (side == 2) return v;
    return 1.0 - u;
}

int taxiLink(int kind, float l, float c) {
    int got = 0;
    int n = uLegCount[kind];
    for (int i = 0; i < n; i++) {
        vec4 g = texelFetch(uLegs, ivec2(i, kind), 0);
        if (l < min(g.x, g.z) - 0.28 || l > max(g.x, g.z) + 0.28 || c < min(g.y, g.w) - 0.28 || c > max(g.y, g.w) + 0.28) continue;
        float dx = g.z - g.x;
        float dy = g.w - g.y;
        float t = clamp(((l - g.x) * dx + (c - g.y) * dy) / (dx * dx + dy * dy), 0.0, 1.0);
        float ex = l - g.x - t * dx;
        float ey = c - g.y - t * dy;
        float d2 = ex * ex + ey * ey;
        if (d2 < 0.014 * 0.014 && c > 0.92) return 2;
        if (d2 < 0.28 * 0.28 && (g.y < 1.0 || g.w < 1.0)) got = 1;
    }
    return got;
}

vec3 base(vec3 col, vec3 ground) {
    return mix(col, ground, 0.08);
}

vec3 apron(float l, float across, vec3 ground) {
    return (fract(l * 2.0) < 0.015 || fract(across * 2.0) < 0.015) ? base(APRON_JOINT, ground) : base(APRON, ground);
}

// Airport paving as the tile recorder packs it; paved is whether something other than the ground came back.
vec3 airportTexel(float u, float v, int aero, vec3 ground, out bool paved) {
    bool along = (aero & 16) != 0;
    float c = abs((along ? v : u) - 0.5);
    float l = along ? u : v;
    int kind = (aero >> 23) & 7;
    int role = aero & 15;
    float ca = 0.0;
    int link = 0;
    if (kind == 3 || kind == 4 || kind == 5) {
        float fx = toward((aero >> 12) & 3, u, v);
        float fy = toward((aero >> 14) & 3, u, v);
        bool alongY = (aero & (1 << 26)) != 0;
        float la = float((aero >> 16) & 15) + (alongY ? fy : fx);
        ca = float((aero >> 20) & 7) + (alongY ? fx : fy);
        if (kind != 3 && !(role == 1 && c <= 0.42)) link = taxiLink(kind, la, ca);
    }
    paved = true;
    if (link == 2) return TAXI_LINE;
    if (role == 1) {
        if (c > 0.42) {
            if (ca < 0.92) { paved = false; return ground; }
            if (kind == 3) return apron(l, ca, ground);
            if (link == 1) return base(ASPHALT, ground);
            paved = false;
            return ground;
        }
        int ends = aero >> 8;
        for (int k = 0; k < 4; k++) {
            if ((ends & (1 << k)) == 0) continue;
            float dist = k == 0 ? v : (k == 1 ? 1.0 - u : (k == 2 ? 1.0 - v : u));
            if (dist < 0.16 && c < 0.33 && fract((along ? v : u) * 12.0) < 0.5) return MARKING;
        }
        if (c < 0.018 && fract(l * 3.0) < 0.55) return MARKING;
        if (c >= 0.37 && c <= 0.395) return MARKING;
        return base(ASPHALT, ground);
    }
    if (role == 2) {
        if (c > 0.3) { paved = false; return ground; }
        return base(STRIP_GRAVEL, ground);
    }
    if (role == 3) {
        if (ca > 1.5 && c > 0.28) return apron(l, ca, ground);
        if (c > 0.28) {
            if (link == 1) return base(ASPHALT, ground);
            paved = false;
            return ground;
        }
        if (c < 0.014) return TAXI_LINE;
        return base(ASPHALT, ground);
    }
    if (role == 4) return apron(l, along ? v : u, ground);
    if (role == 5) {
        float du = abs(u - 0.5);
        float dv = abs(v - 0.5);
        float rr = sqrt(du * du + dv * dv);
        if (du > 0.42 || dv > 0.42) { paved = false; return ground; }
        if (rr >= 0.28 && rr <= 0.31) return TAXI_LINE;
        if ((du >= 0.1 && du <= 0.14 && dv < 0.16) || (du < 0.12 && dv < 0.02)) return MARKING;
        return base(APRON, ground);
    }
    paved = false;
    return ground;
}

float side(int e, float u, float v) {
    return e == 0 ? v : (e == 1 ? 1.0 - u : (e == 2 ? 1.0 - v : u));
}

void main() {
    uint type = (vA.z >> 20) & 3u;
    if (type == 3u) {
        frag = staticTexel(vUv, vEdge, vSpriteSnow);
        return;
    }
    if (type != 0u) {
        frag = vec4(type == 1u ? EARTH_DARK : EARTH_LIGHT, 1.0);
        return;
    }
    vec2 uv = clamp(vUv.xy, 0.0, 0.99999);
    float u = uv.x;
    float v = uv.y;
    tc = (vec2(float(vA.x & 3u), float((vA.x >> 16) & 3u)) + uv) * 0.25;
    gx = dFdx(tc);
    gy = dFdy(tc);
    int kind = int(vA.z & 15u);
    vec4 h = vec4(float(vA.y & 255u), float((vA.y >> 8) & 255u), float((vA.y >> 16) & 255u), float(vA.y >> 24));
    bool alongRL = h.y == h.w;
    vec2 g1 = alongRL ? vec2(h.y - h.x, h.w - h.x) : vec2(h.y - h.x, h.z - h.y);
    vec2 g2 = alongRL ? vec2(h.z - h.w, h.z - h.y) : vec2(h.z - h.w, h.w - h.x);
    bool second = alongRL ? u + v > 1.0 : u < v;
    bool sloped = second ? (g2.x != 0.0 || g2.y != 0.0) : (g1.x != 0.0 || g1.y != 0.0);
    float f = float(second ? (vB.y >> 16) : (vB.y & 0xffff));
    bool water = kind == 7;
    int road = int(vA.w & 0xffffu);
    int aero = vB.x;
    int banks = int((vA.z >> 25) & 15u);
    float charred = float((vA.w >> 16) & 255u) / 255.0;
    vec4 bw = vec4((1.0 - u) * (1.0 - v), u * (1.0 - v), u * v, (1.0 - u) * v);
    bool snowy = vSnow.x + vSnow.y + vSnow.z + vSnow.w > 0.0;
    bool icy = water && vIce.x + vIce.y + vIce.z + vIce.w > 0.0;
    bool snowShows = snowy && (!water || icy || banks != 0);
    bool buried = snowShows && road == 0 && aero == 0 && min(min(vSnow.x, vSnow.y), min(vSnow.z, vSnow.w)) >= 18.0;
    vec3 texel;
    bool roadHere = false;
    if (buried) texel = K(5);
    else {
        texel = K(kind);
        float rag = ragAt(tc);
        int bare = (kind == 0 || kind == 1 || kind == 2 || kind == 4) ? 8 : (kind == 5 ? 3 : -1);
        // Bare ground and thin snow in patches, from the noise read somewhere else, a third and a half of the sheet on.
        if (sloped && bare >= 0 && ragAt(tc + vec2(85.0, 128.0) / 256.0) > 0.64) texel = K(bare);
        for (int e = 0; e < 4; e++) {
            int other = int((vA.z >> uint(4 + 4 * e)) & 15u);
            if (other == 15) continue;
            float dist = side(e, u, v);
            float reach = 0.08 + rag * 0.26;
            if (dist < reach) texel = K(other);
            else if (water && dist < reach + 0.07 && !icy) texel = FOAM;
        }
        vec4 grade = uGrade[kind];
        if (grade.a > 0.0) texel = mix(texel, grade.rgb, grade.a);
        if (vMud > 0.0) texel = mix(texel, MUD, vMud);
        if (charred > 0.0) texel = mix(texel, CHARCOAL, charred * (0.85 + rag * 0.15));
        if (road != 0) {
            int part;
            texel = roadTexel(u, v, road, texel, part);
            roadHere = part != 0;
        }
        bool aeroHere = false;
        if (aero != 0) texel = airportTexel(u, v, aero, texel, aeroHere);
        if (icy) {
            float ice = dot(vIce, bw);
            if (ice >= 4.0) texel = mix(texel, mix(ICE, ICE_DARK, rag * 0.6), min(0.5 + ice / 60.0, 0.92));
        }
        if (banks != 0) for (int k = 0; k < 4; k++) {
            if ((banks & (1 << k)) != 0 && side(k, u, v) < 0.11) texel = mix(STONE, K(3), 0.3);
        }
        if (snowShows) {
            float depth = dot(vSnow, bw);
            float cover = clamp((depth - (0.5 + ragAt(tc + vec2(128.0, 85.0) / 256.0) * 14.0)) / 3.0, 0.0, 1.0);
            if (cover > 0.0) {
                // Traffic packs the snow on a road down to a greyer track.
                bool onRoad = (road != 0 && roadHere) || (aero != 0 && aeroHere);
                texel = mix(texel, K(5), onRoad ? cover * 0.8 : cover);
                if (onRoad) texel = mix(texel, PACKED_SNOW, cover * 0.45);
            }
        }
    }
    float contour = 1.0;
#ifdef OVERLAYS
    // Overlays, as the tile recorder painted them at each level's own size, hw the half tile there.
    float hw = 128.0 / float(1 << uLevel);
    if (vWear > 0) texel = mix(texel, WEAR[vWear - 1], 0.6);
    if ((uOverlay & $SNOW) != 0) {
        float depth = dot(vSnow, bw);
        if (icy) texel = mix(texel, ICE_BAND, 0.6);
        else if (!water && depth >= 1.0) texel = mix(texel, SNOW_BAND[depth < 10.0 ? 0 : (depth < 30.0 ? 1 : (depth < 60.0 ? 2 : 3))], 0.6);
    }
    if ((uOverlay & $HEIGHTS) != 0 && !water) {
        vec2 g = second ? g2 : g1;
        float p0 = second && alongRL ? h.y + h.w - h.z : h.x;
        float at = p0 + g.x * u + g.y * v;
        texel = mix(texel, HEIGHT[clamp(int(at / 2.0), 0, 11)], 0.55);
        // A contour where a slope crosses a band's edge, about a pixel wide.
        if (sloped && mod(at, 2.0) < 1.4 / hw) contour = 0.55;
    }
#endif
    float rel = dot(vRel, bw);
    bool soft = ((vA.z >> 23) & 1u) != 0u;
    float sun = soft && f <= 256.0 ? min(256.0, dot(vLight, bw)) : f;
    float dark = 0.24 - max(0.0, 256.0 - sun) * (0.4 / 256.0);
    float shade = sun * (1.0 + clamp(rel * 0.1, -dark, 0.18));
    if (((vA.z >> 24) & 1u) != 0u) {
        float up = dot(vUp, bw);
        if (up < 0.4 && (road == 0 || !roadHere)) {
            float s = 1.0 - up / 0.4;
            shade *= 1.0 - 0.1 * s * s;
        }
    }
    shade *= contour;
    vec3 col = min(texel * floor(shade) / 256.0, vec3(1.0));
#ifdef OVERLAYS
    // Grid lines light on dark ground and dark on light, so they show on both.
    if ((uOverlay & $GRID) != 0 && hw >= 16.0 && (u < 1.0 / hw || v < 1.0 / hw)) {
        col = floor(col * 255.0) / 255.0;
        col = col.r + col.g + col.b > 380.0 / 255.0 ? floor(col * 255.0 * 150.0 / 256.0) / 255.0 : mix(col, GRID_LIGHT, 0.4);
    }
#endif
    frag = vec4(col, 1.0);
}
"""

/**
 * [source] with the overlays' code in. It's left out of the ground program drawn with none on, as it cost the Fire
 * about 8 fps in a pinch sweep even with every overlay off.
 */
internal fun withOverlays(source: String) = source.replaceFirst("#version 300 es\n", "#version 300 es\n#define OVERLAYS\n")
