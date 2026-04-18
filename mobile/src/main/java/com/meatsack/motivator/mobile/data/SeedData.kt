package com.meatsack.motivator.mobile.data

import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.model.Message

object SeedData {

    fun getPreWrittenMessages(): List<Message> = buildList {
        // ── Level 1: AGGRESSIVE ── Inactivity ──
        addAll(inactivityLevel1Full)
        addAll(inactivityLevel1WorkSafe)
        // ── Level 2: SAVAGE ── Inactivity ──
        addAll(inactivityLevel2Full)
        addAll(inactivityLevel2WorkSafe)
        // ── Level 3: NUCLEAR ── Inactivity ──
        addAll(inactivityLevel3Full)
        addAll(inactivityLevel3WorkSafe)
        // ── Level 4: EXISTENTIAL ── Inactivity ──
        addAll(inactivityLevel4Full)
        addAll(inactivityLevel4WorkSafe)
    }

    private fun msg(
        text: String,
        level: EscalationLevel,
        trigger: TriggerType = TriggerType.INACTIVITY,
        tone: MessageTone = MessageTone.FULL_SEND,
    ) = Message(
        text = text,
        level = level,
        triggerType = trigger,
        tone = tone,
        source = MessageSource.PRE_WRITTEN,
    )

    // ── LEVEL 1: AGGRESSIVE ─────────────────────────────────────

    private val inactivityLevel1Full = listOf(
        msg("Are you glued to that chair? GET UP, you lazy sack of shit.", EscalationLevel.AGGRESSIVE),
        msg("30 minutes of nothing. You're pathetic. Move.", EscalationLevel.AGGRESSIVE),
        msg("Hey, you sedentary piece of gristle. Your legs still work. USE THEM.", EscalationLevel.AGGRESSIVE),
        msg("GET UP, you domesticated sloth.", EscalationLevel.AGGRESSIVE),
        msg("You've been still so long your muscles are filing a missing persons report.", EscalationLevel.AGGRESSIVE),
        msg("Move it, you couch-welded disappointment.", EscalationLevel.AGGRESSIVE),
        msg("Your chair is not your coffin. Not yet. GET UP.", EscalationLevel.AGGRESSIVE),
        msg("Still sitting? You oxygen-wasting chair ornament. MOVE.", EscalationLevel.AGGRESSIVE),
        msg("GET UP, you overfed house cat.", EscalationLevel.AGGRESSIVE),
        msg("30 minutes. You've done nothing. You're a waste of a heartbeat right now.", EscalationLevel.AGGRESSIVE),
        msg("Your ancestors didn't survive plagues for you to sit there like a lump. MOVE.", EscalationLevel.AGGRESSIVE),
        msg("Get off your ass, you cortisol-soaked quitter.", EscalationLevel.AGGRESSIVE),
    )

    private val inactivityLevel1WorkSafe = listOf(
        msg("Are you glued to that chair? GET UP.", EscalationLevel.AGGRESSIVE, tone = MessageTone.WORK_SAFE),
        msg("30 minutes of nothing. Pathetic. Move.", EscalationLevel.AGGRESSIVE, tone = MessageTone.WORK_SAFE),
        msg("Your muscles are filing a missing persons report. MOVE.", EscalationLevel.AGGRESSIVE, tone = MessageTone.WORK_SAFE),
        msg("Move it. Now. You're better than this. Barely.", EscalationLevel.AGGRESSIVE, tone = MessageTone.WORK_SAFE),
    )

    // ── LEVEL 2: SAVAGE ─────────────────────────────────────────

    private val inactivityLevel2Full = listOf(
        msg("One hour. Your muscles are literally eating themselves. You're choosing to rot.", EscalationLevel.SAVAGE),
        msg("You're choosing to rot right now. You know that, right?", EscalationLevel.SAVAGE),
        msg("Still sitting, you sarcopenic motherfucker? Your body is decomposing in real time.", EscalationLevel.SAVAGE),
        msg("An hour of nothing. You osteopenic jello mold. STAND UP.", EscalationLevel.SAVAGE),
        msg("Your joints are fusing together while you sit there, you arthritic waste.", EscalationLevel.SAVAGE),
        msg("One hour. A hibernating waste of a pulse. That's what you are.", EscalationLevel.SAVAGE),
        msg("You gravity-surrendering meatsack. One hour of decay. GET UP.", EscalationLevel.SAVAGE),
        msg("Still here? You Netflix-marinated excuse machine. Your body hates you.", EscalationLevel.SAVAGE),
        msg("Your skeleton is becoming decorative. One hour, you osteoporotic coward. MOVE.", EscalationLevel.SAVAGE),
        msg("An hour. You elevator-taking, stair-fearing fraud. STAND UP AND WALK.", EscalationLevel.SAVAGE),
    )

    private val inactivityLevel2WorkSafe = listOf(
        msg("One hour. Your muscles are literally eating themselves. Move.", EscalationLevel.SAVAGE, tone = MessageTone.WORK_SAFE),
        msg("You're choosing to rot right now. One hour of nothing.", EscalationLevel.SAVAGE, tone = MessageTone.WORK_SAFE),
        msg("Your joints are fusing. One hour. GET UP.", EscalationLevel.SAVAGE, tone = MessageTone.WORK_SAFE),
    )

    // ── LEVEL 3: NUCLEAR ────────────────────────────────────────

    private val inactivityLevel3Full = listOf(
        msg("Your ancestors survived wars and you can't walk to the kitchen, you arthritic jello mold.", EscalationLevel.NUCLEAR),
        msg("You weak, excuse-making piece of shit. STAND UP.", EscalationLevel.NUCLEAR),
        msg("90 minutes of nothing. You sarcopenic pansy ass motherfucker. MOVE.", EscalationLevel.NUCLEAR),
        msg("You're a weakness-worshipping fraud. Your body is giving up on you because YOU gave up first.", EscalationLevel.NUCLEAR),
        msg("You soft-bellied comfort addict. 90 minutes. Your muscles are screaming and you can't hear them over your excuses.", EscalationLevel.NUCLEAR),
        msg("GET UP, you osteopenic waste of a skeleton. 90 goddamn minutes.", EscalationLevel.NUCLEAR),
        msg("Your bones are turning to chalk while you sit there, you pathetic snooze-button-hitting disgrace.", EscalationLevel.NUCLEAR),
        msg("You potential-wasting coward. 90 minutes of choosing to be weak.", EscalationLevel.NUCLEAR),
    )

    private val inactivityLevel3WorkSafe = listOf(
        msg("Your ancestors survived wars and you can't walk to the kitchen.", EscalationLevel.NUCLEAR, tone = MessageTone.WORK_SAFE),
        msg("90 minutes. You're a weakness-worshipping fraud. STAND UP.", EscalationLevel.NUCLEAR, tone = MessageTone.WORK_SAFE),
    )

    // ── LEVEL 4: EXISTENTIAL ────────────────────────────────────

    private val inactivityLevel4Full = listOf(
        msg("2 hours of nothing. Two hours closer to death, spent getting weaker.", EscalationLevel.EXISTENTIAL),
        msg("What are you even doing with your life?", EscalationLevel.EXISTENTIAL),
        msg("2 hours. You beached fucking walrus of a human. Every minute is a choice and you keep choosing weakness.", EscalationLevel.EXISTENTIAL),
        msg("Two hours. Your body is a temple and you've turned it into a landfill. GET UP.", EscalationLevel.EXISTENTIAL),
        msg("You've been sitting so long your blood has a parking brake on it. TWO HOURS. MOVE.", EscalationLevel.EXISTENTIAL),
        msg("2 hours of bone-density-losing, muscle-wasting, excuse-making NOTHING. Is this who you are?", EscalationLevel.EXISTENTIAL),
        msg("Two hours. You're not resting. You're practicing being dead.", EscalationLevel.EXISTENTIAL),
        msg("Is this it? Is this your life? Sitting here while your body rots? GET UP.", EscalationLevel.EXISTENTIAL),
    )

    private val inactivityLevel4WorkSafe = listOf(
        msg("2 hours of nothing. Two hours closer to death, spent getting weaker.", EscalationLevel.EXISTENTIAL, tone = MessageTone.WORK_SAFE),
        msg("What are you even doing with your life? Two hours. MOVE.", EscalationLevel.EXISTENTIAL, tone = MessageTone.WORK_SAFE),
    )
}
