package com.example.mygrill.core

/** Moments of a cook worth telling the owner about, even with the phone in a pocket. */
enum class CookEvent { STARTED, PREHEAT_DONE, FLIP, DONE, TURNED_OFF, PULL_SOON, PULL_NOW, STOP_FAILED, LID_OPEN }

object CookEvents {
    /**
     * What changed between two consecutive merged views. Nothing on the first view (the app just
     * opened onto a cook already running) or while the grill isn't heard: only real transitions.
     * The caller drops repeats within one cook, since a phase can flicker between channels.
     */
    fun between(prev: GrillView?, now: GrillView?): List<CookEvent> {
        if (prev == null || now == null) return emptyList()
        return buildList {
            if (!prev.phase.running && now.phase.heating) add(CookEvent.STARTED)
            val wasPreheating = prev.phase == CookPhase.PREHEAT || prev.phase == CookPhase.IGNITION
            // Not on "waiting for the lid": opening the lid mid-preheat says nothing about the heat.
            if ((wasPreheating && now.phase == CookPhase.COOKING) ||
                (prev.prompt != Prompt.ADD_FOOD && now.prompt == Prompt.ADD_FOOD)
            ) add(CookEvent.PREHEAT_DONE)
            if (prev.prompt != Prompt.FLIP_FOOD && now.prompt == Prompt.FLIP_FOOD) add(CookEvent.FLIP)
            if (!prev.finished && now.finished) add(CookEvent.DONE)
            if (prev.phase != CookPhase.OFF && now.phase == CookPhase.OFF) add(CookEvent.TURNED_OFF)
        }
    }

    private val CookPhase.running get() = heating || this == CookPhase.WAITING_LID
    private val GrillView.finished get() = phase == CookPhase.DONE || prompt == Prompt.DONE || prompt == Prompt.GET_FOOD
}
