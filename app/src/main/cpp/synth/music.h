// Generative music for the MUSIC bus: calm pieces by era and season, composed a bar at a time on
// the audio thread from small tables, with quiet gaps between pieces. Everything is fixed size and
// made in the constructor, so nothing allocates or locks while it plays.
#pragma once

#include <atomic>
#include <cstdint>

#include "dsp.h"

namespace interchange {

/** How the music sounds in each era. */
namespace musicStyle {
constexpr int PARLOUR = 0;    // ragtime-ish piano, a muted cornet
constexpr int SWING = 1;      // walking bass, reeds, vibes, brushes
constexpr int SIXTIES = 2;    // electric piano, bass guitar, flute, a light bossa beat
constexpr int SYNTH_POP = 3;  // analogue pads, arpeggios, a soft drum machine
constexpr int AMBIENT = 4;    // long pads, bells, a deep bass, no beat
constexpr int FUTURE = 5;     // glassy FM pads, slow plucks, shimmer
constexpr int COUNT = 6;
}  // namespace musicStyle

/** The style for a game year. */
int musicStyleOf(int year);

/** Seasons as the game sends them. */
namespace musicSeason {
constexpr int SPRING = 0;
constexpr int SUMMER = 1;
constexpr int AUTUMN = 2;
constexpr int WINTER = 3;
}  // namespace musicSeason

/** Which part a note belongs to, for the note hook. */
namespace musicPart {
constexpr int BASS = 0;
constexpr int CHORD = 1;
constexpr int MELODY = 2;
constexpr int COUNTER = 3;
constexpr int DRUMS = 4;
}  // namespace musicPart

/** A note as composed, for tests through [Music::noteHook]. [step] is 16ths into the bar. */
struct MusicNote {
    int piece = 0;
    int style = 0;
    int season = 0;
    int bar = 0;
    int step = 0;
    int part = 0;
    int instrument = 0;
    int midi = 0;
    float velocity = 0;
};

class Music {
public:
    explicit Music(float sampleRate);

    /** Game thread: the year and season (musicSeason) to play for. Year 0 fades out and stays quiet. */
    void set(int year, int season, uint32_t seed);

    /** Audio thread: one stereo frame. */
    void next(float& left, float& right);

    /** A piece is sounding, not in a gap between pieces. */
    bool playing() const { return state_ == PLAYING; }

    /** Pieces started so far. */
    int pieces() const { return piece_; }

    /** Samples played so far; it stands still while the music isn't worked out. */
    int64_t time() const { return now_; }

    /** Host tests only: called on the audio thread for every note composed. */
    void (*noteHook)(void* user, const MusicNote& note) = nullptr;
    void* hookUser = nullptr;

    static constexpr int kVoices = 24;
    static constexpr int kPending = 160;
    static constexpr int kInstruments = 32;

    struct Voice {
        bool on = false;
        bool releasing = false;
        int instrument = 0;
        float freq = 0;
        float velocity = 0;
        float panLeft = 0, panRight = 0;
        int gate = 0;       // samples left with the key down
        int stage = 0;      // 0 attack, 1 decay, 2 release
        float env = 0;
        float fenv = 0;     // filter and FM brightness, falling from 1
        float pitchEnv = 0;
        float lfo = 0;      // vibrato and tremolo phase, 0..1
        float pitch = 1;    // vibrato, worked out at control rate
        float tremolo = 1;
        float phase[3] = {};
        Svf filter;
        Rng rng;
    };

    struct Pending {
        int64_t at = 0;
        int instrument = 0;
        int midi = 0;
        float velocity = 0;
        float pan = 0;
        int length = 0;     // samples with the key down
    };

    /** Two bars of melody: notes at 16th steps, as scale degrees from the tonic. */
    struct Motif {
        int count = 0;
        uint8_t step[16] = {};
        uint8_t length[16] = {};
        int8_t degree[16] = {};
    };

    struct Section {
        int bars = 8;
        int progression = 0;
        int motif = 0;       // -1 for no melody
        int variant = 0;     // how many times this motif has come round before
        bool counter = false;
    };

private:
    enum State { WAITING, PLAYING };

    void control();
    void beginPiece(int year, int season);
    void composeBar();
    void endPiece(float gapSeconds);
    void makeMotif(Motif& m, uint32_t seed);
    void makeMotifOnce(Motif& m, uint32_t seed);
    void add(int part, int step, int instrument, int midi, float velocity, float lengthSteps, float pan);
    void start(const Pending& p);
    float render(Voice& v, bool controlTick);

    int chordAt(int sectionIndex, int barInSection) const;
    int degreeMidi(int degree, int octaveBase) const;
    int nearest(int degree, int centre) const;
    int chordTone(int chord, int degree) const;

    float sampleRate_;
    std::atomic<int> yearIn_{0};
    std::atomic<int> seasonIn_{0};
    std::atomic<uint32_t> seedIn_{1};

    State state_ = WAITING;
    int64_t now_ = 0;
    int64_t startAt_ = 0;
    int64_t nextBarAt_ = 0;
    int64_t endAt_ = 0;
    int ctl_ = 0;
    int piece_ = 0;
    uint32_t pieceSeed_ = 1;
    bool stopping_ = false;
    Smooth fade_;

    // The piece now.
    int style_ = 0;
    int season_ = 0;
    int root_ = 50;
    const int* mode_ = nullptr;
    float bpm_ = 80;
    float swing_ = 0;
    float density_ = 1;
    bool drums_ = false;
    int barSamples_ = 0;
    int stepSamples_ = 0;
    int bar_ = 0;
    int totalBars_ = 0;
    int sectionCount_ = 0;
    int section_ = 0;
    int barInSection_ = 0;
    int progressions_[3] = {};
    Section sections_[10];
    Motif motifs_[3];
    int lastBass_ = 0;
    Rng rng_;

    Voice voices_[kVoices];
    Pending pending_[kPending];
    int pendingCount_ = 0;
    int64_t nextDue_ = INT64_MAX;
    OnePole air_[2];

    struct Coeffs {
        float attack = 0, decay = 0, release = 0, fenv32 = 0, pitch = 0;
    };
    Coeffs coeffs_[kInstruments];
};

}  // namespace interchange
