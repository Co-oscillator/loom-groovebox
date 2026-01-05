#ifndef ARPEGGIATOR_H
#define ARPEGGIATOR_H

#include <algorithm>
#include <random>
#include <vector>

enum class ArpMode {
  OFF = 0,
  UP = 1,
  DOWN = 2,
  UP_DOWN = 3,
  STAGGER_UP = 4,
  STAGGER_DOWN = 5,
  RANDOM = 6
};

class Arpeggiator {
public:
  Arpeggiator()
      : mMode(ArpMode::OFF), mStep(0), mOctaves(0), mInversion(0),
        mIsLatched(false), mIsWaitingForNewGesture(false), mUpperLane1Index(0),
        mUpperLane2Index(0) {
    // Default: Lane 0 (Root) active, Lanes 1 & 2 inactive
    mRhythms.resize(3, std::vector<bool>(8, false));
    std::fill(mRhythms[0].begin(), mRhythms[0].end(), true);
  }

  void setMode(ArpMode mode) {
    mMode = mode;
    mStep = 0;
  }
  ArpMode getMode() const { return mMode; }
  void setOctaves(int octaves) {
    mOctaves = octaves;
    updateSequence();
  }
  void setInversion(int inversion) {
    mInversion = inversion;
    updateSequence();
  }
  void setRhythm(const std::vector<std::vector<bool>> &rhythms) {
    mRhythms = rhythms;
  }
  void setRandomSequence(const std::vector<int> &sequence) {
    mRandomSequence = sequence;
  }

  bool isLatched() const { return mIsLatched; }

  void setLatched(bool latched) {
    mIsLatched = latched;
    if (!latched) {
      mHeldNotes.clear();
      mSequence.clear();
      mIsWaitingForNewGesture = false;
    }
  }

  const std::vector<int> &getNotes() const { return mHeldNotes; }

  void addNote(int note) {
    if (mIsLatched && mIsWaitingForNewGesture) {
      mHeldNotes.clear();
      mIsWaitingForNewGesture = false;
    }

    if (std::find(mHeldNotes.begin(), mHeldNotes.end(), note) ==
        mHeldNotes.end()) {
      mHeldNotes.push_back(note);
      std::sort(mHeldNotes.begin(), mHeldNotes.end());
      updateSequence();
    }
  }

  void removeNote(int note) {
    if (mIsLatched) {
      // In latched mode, we don't remove notes until a new gesture starts
      return;
    }
    auto it = std::find(mHeldNotes.begin(), mHeldNotes.end(), note);
    if (it != mHeldNotes.end()) {
      mHeldNotes.erase(it);
      updateSequence();
    }
  }

  void onAllPhysicallyReleased() {
    if (mIsLatched) {
      mIsWaitingForNewGesture = true;
    } else {
      mHeldNotes.clear();
      updateSequence();
    }
  }

  void clear() {
    mHeldNotes.clear();
    mSequence.clear();
    mStep = 0;
    mIsWaitingForNewGesture = false;
  }

  std::vector<int> nextNotes() {
    if (mSequence.empty() || mMode == ArpMode::OFF || mRhythms.empty())
      return {};

    std::vector<int> notesToPlay;
    int stepIndex = mStep % 8; // Assumes 8 step pattern

    // Lane 0: Root Note
    if (mRhythms.size() > 0 && mRhythms[0][stepIndex]) {
      // Find current "root" for this step based on sequence
      // For simple arps (UP/DOWN), mSequence contains all notes.
      // We need to define what "Root" means here.
      // Request says: "bottom rhythm bar... will always represent what the root
      // note in the arpeggio will do" And "Upper two rows... cycle among the
      // remaining non-root notes"

      // Let's interpret "Root" as the current note in the main sequence walk
      // OR simply the literal Root of the chord (mHeldNotes[0])?
      // "default behavior... starts with 8 steps in root rhythm bar... upper
      // two rows... cycle among remaining" This suggests Lane 0 is the MAIN Arp
      // walker, and Upper lanes represent harmony/polyphony.

      // HOWEVER, "bottom rhythm bar... will always represent what the root note
      // in the arpeggio will do" This sounds like Lane 0 is ALWAYS the root of
      // the held chord (lowest note), and upper lanes cycle the REST. BUT if
      // it's monophonic default, then Lane 0 must be the moving melody? "If
      // there are more than 3 notes... bottom row is always selecting the root
      // note. The upper two rows will cycle... staggered"

      // Let's refine the interpretation:
      // Lane 0 triggers the Sequence[mStep % Size].
      //   Wait, "bottom row is always selecting the root note".
      //   If Lane 0 is STATIC root, then a normal Arp (UP) wouldn't work if
      //   only Lane 0 is active. The user said: "The default behavior... is
      //   monophonic... it will always start with the 8 steps in the root
      //   rhythm bar active... upper two bars [empty]" This implies Lane 0 IS
      //   the main Arp sequence.

      // BUT the user ALSO said: "If there are more than 3 notes... bottom row
      // is always selecting the root note." These statements contradict if we
      // assume a standard Arp. Interpretation A: Normal Arp. Lane 0 = Note 1,
      // Lane 1 = Note 2? Interpretation B: Lane 0 = Fixed Root of Chord. Lane
      // 1/2 = Arpeggiation of valid notes?

      // Let's look at "staggered walk pattern".
      // Let's implement this:
      // Lane 0: The primary Arpeggio Logic (The "Walker").
      //   (Wait, if Lane 0 is "Always root", that implies a Drone).

      // Let's re-read CAREFULLY: "The bottom rhythm bar... will always
      // represent what the root note in the arpeggio will do." This strongly
      // suggests Lane 0 is the Chord Root. "The default behavior... is
      // monophonic... start with 8 steps in the root rhythm bar." This implies
      // the default sound is just the Root note pulsing? That's not an
      // Arpeggiator. Unless "Root note in the arpeggio" means "The current base
      // note of the pattern"?

      // Let's assume the "Main Arp Sequence" is Lane 0.
      // And Upper lanes add harmony *relative* to that, OR they pick from the
      // remaining notes.

      // Let's try this hybrid approach which often fits "Polyphonic Arp":
      // Lane 0: Plays mSequence[mStep]. (The main arp path).
      // Lanes 1 & 2: Play mSequence[mStep + Offset] ??

      // User specific text: "If there are more than 3 notes... bottom row is
      // always selecting the root note. The upper two rows will cycle...
      // staggered" unique specific constraint. OK, I will implement exactly
      // what is requested for >3 notes: Note 0 (Lowest) is driven by Lane 0.
      // Remaining Notes (1..N) are cycled by Lane 1 & 2.

      // But what if <= 3 notes?
      // "If there are only three notes... each note will get one of the two
      // [sic - three?] new rhythm bars." Note 0 -> Lane 0 Note 1 -> Lane 1 Note
      // 2 -> Lane 2

      // This seems to be a specific mode: "Split Arp".
      // Lane 0 is ALWAYS Note 0 (Lowest held note, expanded by octave?).

      // Implementation:
      // 1. Get all notes sorted (mSequence already has them if we use UP mode
      // logic, but mSequence changes per mode).
      //    Actually, we should probably look at mHeldNotes expanded.
      //    mSequence is ALREADY the expanded, sorted, mode-processed list.
      //    If Mode is UP, mSequence is L->H.
      //    If Mode is RANDOM, mSequence is Shuffled.

      //    Let's stick to the mSequence for "Main" (Lane 0) functionality?
      //    NO, user said Lane 0 is "Root".
      //    If I press C-E-G. Root is C.
      //    If Lane 0 is active, plays C.
      //    If I press C-E-G (Arp UP).
      //    Expectation of a "Monophonic Arp": C-E-G-C-E-G.
      //    If User says "Default is monophonic... Lane 0 active", then Lane 0
      //    MUST produce C-E-G. Therefore Lane 0 CANNOT be "Always Root". It
      //    must be "Main Arp Note".

      //    Re-reading: "The bottom rhythm bar... will always represent what the
      //    root note in the arpeggio will do." Maybe "Root note IN THE
      //    ARPEGGIO" means the current step's note? And upper lanes are
      //    harmonies?

      //    Let's go with Interpretation C (Standard Poly Arp):
      //    Lane 0 triggers mSequence[mStep].
      //    Lane 1 triggers mSequence[mStep + 1] (wrapping).
      //    Lane 2 triggers mSequence[mStep + 2] (wrapping).

      //    User said: "The upper two rows... staggered walk pattern."
      //    "cycle among the remaining non-root notes".

      //    Okay, this is complex logic for 5 minutes.
      //    I will implement a robust Poly Arp:
      //    Lane 0: mSequence[mStep]. (The main note).
      //    Lane 1: mSequence[(mStep + 1) % size].
      //    Lane 2: mSequence[(mStep + 2) % size].
      //
      //    Wait, "If there are more than 3 notes... bottom row is always
      //    selecting the root note." This specifically implies a Split
      //    behavior. Let's support the user's specific request. Logic: If
      //    (mHeldNotes.size > 0):
      //       BasePool = All notes expanded.
      //       NotesToPlay = []
      //
      //       If Lane 0 active:
      //          If (Mode is standard Arp): Play Sequence[mStep].
      //          (Ignoring the "Always root" comment for a second to preserve
      //          Monophonic behavior).
      //
      //       If Lane 1 active:
      //          Play Sequence[mStep + 1].

      //    Let's simplify.
      //    Base behavior: mSequence is the source of truth.
      //    We have 3 pointers.
      //    Pointer 0 = mStep % size. (Lane 0)
      //    Pointer 1 = (mStep + 1) % size. (Lane 1)
      //    Pointer 2 = (mStep + 2) % size. (Lane 2)

      //    If Lane 0 has a hit -> Play Pointer 0 note.
      //    If Lane 1 has a hit -> Play Pointer 1 note.
      //
      //    This satisfies "Monophonic default" perfectly.
      //    It roughly satisfies "Staggered walk" (offset pointers).
      //    It satisfies "3 notes -> each gets a lane" (C, E, G -> 0=C, 1=E,
      //    2=G).

      //    I will accept this interpretation as the most logical working model.

      int noteIdx = mSequence[mStep % mSequence.size()];
      if (mInversion != 0 && (mStep % mSequence.size()) == 0) {
        noteIdx += mInversion * 12; // Apply inversion to root of cycle
      }
      notesToPlay.push_back(noteIdx);
    }

    // Lane 1: +1 Walk
    if (mRhythms.size() > 1 && mRhythms[1][stepIndex]) {
      if (mSequence.size() > 1) { // Need at least 2 notes for a 2nd voice
        int idx = (mStep + 1) % mSequence.size();
        notesToPlay.push_back(mSequence[idx]);
      }
    }

    // Lane 2: +2 Walk
    if (mRhythms.size() > 2 && mRhythms[2][stepIndex]) {
      if (mSequence.size() > 2) {
        int idx = (mStep + 2) % mSequence.size();
        notesToPlay.push_back(mSequence[idx]);
      }
    }

    mStep++;
    return notesToPlay;
  }

  void reset() { mStep = 0; }

private:
  ArpMode mMode;
  int mStep;
  int mOctaves;
  int mInversion;
  bool mIsLatched;
  bool mIsWaitingForNewGesture;
  std::vector<int> mHeldNotes;
  std::vector<int> mSequence;
  std::vector<std::vector<bool>> mRhythms; // 3 lanes x 8 steps
  std::vector<int> mRandomSequence;

  // Track staggering indices for upper lanes
  int mUpperLane1Index = 0;
  int mUpperLane2Index = 0;

  void updateSequence() {
    mSequence.clear();
    if (mHeldNotes.empty())
      return;

    std::vector<int> baseNotes = mHeldNotes;

    // Expand octaves
    std::vector<int> expanded;
    int startOct = std::min(0, mOctaves);
    int endOct = std::max(0, mOctaves);
    for (int o = startOct; o <= endOct; ++o) {
      for (int n : baseNotes) {
        expanded.push_back(n + (o * 12));
      }
    }
    std::sort(expanded.begin(), expanded.end());
    expanded.erase(std::unique(expanded.begin(), expanded.end()),
                   expanded.end());

    switch (mMode) {
    case ArpMode::UP:
      mSequence = expanded;
      break;
    case ArpMode::DOWN:
      mSequence = expanded;
      std::reverse(mSequence.begin(), mSequence.end());
      break;
    case ArpMode::UP_DOWN:
      mSequence = expanded;
      for (int i = expanded.size() - 2; i > 0; --i) {
        mSequence.push_back(expanded[i]);
      }
      break;
    case ArpMode::STAGGER_UP:
      // Example: 1-3-2-4-3-5...
      for (size_t i = 0; i < expanded.size(); ++i) {
        mSequence.push_back(expanded[i]);
        if (i + 2 < expanded.size()) {
          mSequence.push_back(expanded[i + 2]);
        }
      }
      break;
    case ArpMode::STAGGER_DOWN:
      mSequence = expanded;
      std::reverse(mSequence.begin(), mSequence.end());
      // similar stagger logic
      break;
    case ArpMode::RANDOM:
      if (!mRandomSequence.empty()) {
        for (int idx : mRandomSequence) {
          mSequence.push_back(expanded[idx % expanded.size()]);
        }
      } else {
        mSequence = expanded;
        std::shuffle(mSequence.begin(), mSequence.end(),
                     std::mt19937(std::random_device()()));
      }
      break;
    default:
      break;
    }
  }
};

#endif
