# meatsackMotivator — Design Spec

## Overview

**meatsackMotivator** is a Samsung Galaxy Watch app (Wear OS) that monitors health/activity data and delivers aggressive, profane, David Goggins-style motivational insults when the wearer is inactive. It features escalating intensity, AI-generated messages via Claude API, a user voting system that curates the insult library over time, and a phone companion app for configuration and message management.

**Target devices:** Galaxy Watch 4, 5, 6, 7, Ultra (Wear OS 3.0+)

**Tone:** David Goggins as an angry, disgusted drill sergeant. Creative compound insults using medical/anatomical terms (sarcopenic, osteopenic, arthritic, osteoporotic) combined with profanity. Attacks laziness, weakness, comfort-seeking, excuse-making. Never uses "fat" as an insult — targets character and physical decay from inactivity.

## System Architecture

Three components:

- **Watch app (Wear OS)** — The delivery device. Monitors health sensors, manages escalation timers, stores a local cache of insults, handles screen takeover + haptic vibration + vote UX. Works offline using cached messages.
- **Phone companion app (Android)** — The brain. Handles configuration (goals, schedule, intensity), manages the master insult library (pre-written + AI-generated + user-curated), calls Claude API to generate fresh messages, syncs insult cache to watch via Wear OS Data Layer API.
- **Claude API** — Called from the phone only (battery/connectivity). Generates batches of messages using the user's current stats and upvoted message style as context.

## Health Data Sources

| Sensor | What it feeds | Example insult fuel |
|---|---|---|
| Step counter | Hourly pace vs goal, daily total | "312 steps by 2pm. My dead grandmother is lapping you." |
| Heart rate | Sustained low HR = sedentary detection | "Your heart rate has been flatlined all morning. Are you a corpse or just acting like one?" |
| Sleep | Hours slept vs activity output | "9 hours of sleep and 400 steps. You're not recovering, you're decomposing." |
| Floors climbed | Stair/elevation tracking | "Zero floors. You took the elevator again, you sarcopenic waste of legs." |
| Calories | Active vs passive burn | "You've burned 12 active calories today. A coma patient does better." |
| Workout detection | Presence or absence of exercise sessions | "No workout logged. Again. Day 4. You're building a streak of nothing." |

## Trigger System

### Three trigger types

1. **Inactivity trigger** — No significant movement for X minutes (user-configurable, default 30 min). Primary trigger. Fires the first message and starts the escalation clock.
2. **Scheduled check-in** — Hourly comparison of current steps vs pace needed for daily goal. If behind pace, fires a message. Severity scales with how far behind.
3. **End-of-day reckoning** — Evening summary. If goal wasn't hit, a particularly savage message. If it was, grudging respect or (rarely) genuine praise.

### Escalation model

All levels trigger with a single haptic buzz. Escalation increases venom only, not frequency. Fires every 30 minutes while idle.

- **Level 1** — Aggressive. "Are you glued to that chair? GET UP, you lazy sack of shit."
- **Level 2** — Savage. "One hour. Your muscles are literally eating themselves. You're choosing to rot. MOVE."
- **Level 3** — Nuclear. "Your ancestors survived wars and you can't walk to the kitchen, you arthritic jello mold."
- **Level 4** — Existential. "2 hours of nothing. Two hours closer to death, spent getting weaker. What are you even doing with your life?"

Escalation resets when the accelerometer detects sustained movement (not just a wrist flick to dismiss).

### Context-aware language

Toggleable in phone app settings (default: off).

- **On:** Work hours use cleaner-end-of-each-level language. Evening/weekend = full send.
- **Off:** Full send, all day, no filter.
- **Quiet hours:** Configurable sleep window. App is silent.

## Message System

### Three message sources

**1. Pre-written library (~200-300 messages at launch)**

Each message tagged with:
- Escalation level (1-4)
- Trigger type (inactivity, behind pace, end-of-day, no workout)
- Context tone (full send vs work-safe)
- Vote counts (thumbs up / thumbs down)

**2. AI-generated (Claude API, from phone)**

Prompt template:
```
Generate 10 short (1-2 sentence) aggressive motivational insults
for someone who has taken {steps} steps and it's {time}.
Style: David Goggins as an angry, disgusted drill sergeant.
Use creative medical/anatomical compound insults like
'sarcopenic motherfucker', 'osteopenic jello mold',
'arthritic waste of a skeleton', 'osteoporotic coward'.
Attack laziness, weakness, comfort-seeking. Never use 'fat'
as an insult. Max 20 words per message.

Here are messages the user loved — generate more in this style:
- '{top_voted_1}'
- '{top_voted_2}'
- '{top_voted_3}'
- '{top_voted_4}'
- '{top_voted_5}'
```

Top 5-10 upvoted messages are included as style examples every generation. The AI drifts toward what this specific user responds to.

**3. User-curated favorites**

Thumbs-up increments vote count; thumbs-down decrements. Selection algorithm favors higher-voted messages. Messages with 3+ thumbs down stop appearing. Users can review, delete, and write custom messages in the phone app.

### Creative insult vocabulary

Messages use creative compound insults as names/addresses:

- Medical decay: "you sarcopenic motherfucker", "you osteopenic jello mold", "you arthritic waste of a skeleton", "you osteoporotic coward"
- Animal comparisons: "you domesticated sloth", "you overfed house cat", "you hibernating waste of a pulse"
- Creative compounds: "you couch-welded disappointment", "you gravity-surrendering meatsack", "you Netflix-marinated excuse machine", "you oxygen-wasting chair ornament", "you cortisol-soaked quitter", "you elevator-taking, stair-fearing fraud"
- Identity attacks: "you soft-bellied comfort addict", "you weakness-worshipping fraud", "you snooze-button-hitting disgrace", "you potential-wasting coward"

### Message length

1-2 sentences max. Must be readable at a glance on a 1.4" watch screen.

### Selection algorithm

1. Filter by current escalation level + trigger type + context tone
2. Weight by vote score (more thumbs up = higher chance)
3. No repeat within last 24 hours
4. Prefer unvoted AI-generated messages (so user rates new material)

### Sync

Phone pushes a rotating cache of ~50 messages to the watch via Data Layer API. Watch never needs connectivity to deliver insults. Phone refreshes cache when connected.

## Watch UX

### Screen takeover flow

1. Health trigger fires
2. Single strong haptic vibration
3. Message queued as high-priority notification
4. User raises wrist → meatsackMotivator takes over display

### Takeover screen layout

```
┌──────────────────────┐
│                      │
│   GET UP, you        │
│   osteopenic         │
│   jello mold.        │
│                      │
│   438 steps. It's    │
│   2pm. Pathetic.     │
│                      │
│   ┌────┐    ┌────┐   │
│   │ 👎 │    │ 👍 │   │
│   └────┘    └────┘   │
└──────────────────────┘
```

- **Insult message** — large, readable text
- **Stat context line** — small text showing triggering data
- **Vote buttons** — thumbs up/down. Tapping dismisses AND records vote
- **Swipe right** — dismiss without voting

Design should be screenshot-worthy with subtle "meatsackMotivator" branding/watermark for organic social sharing.

### Snooze

No snooze from the takeover screen. Snooze available from watch quick settings toggle:
- 30 minutes
- 1 hour
- 2 hours
- Until tomorrow

## Positive Reinforcement

### Grudging respect (approaching goal / above-average activity)

Fires as a standard notification, NOT screen takeover. Same angry voice, acknowledging effort.

- "Fine. You're actually moving today. Don't let it go to your head, you usually quit by now."
- "7,800 steps. Almost not embarrassing. Keep going."
- "Your heart rate actually broke 100 today. So you ARE alive."

### Rare genuine moments (exceptional days — goal exceeded by 50%+, big workout)

Drops the insult voice briefly. Uncommon enough to hit hard.

- "That's what I'm talking about. Now do it again tomorrow."
- "15,000 steps. You chose hard today. Remember this feeling."
- "Stay hard."

### Streaks

Consecutive days hitting goal:
- 3-day streak: grudging respect
- 7-day streak: rare genuine
- Streak broken: nuclear disappointment. "4 days. You built something and you just threw it away. Weak."

## Phone Companion App

### Home / Dashboard
- Today's stats (steps, HR, floors, calories, workouts)
- Current streak
- Messages delivered today, votes cast
- Escalation status

### Settings
- Daily step goal (default: 10,000)
- Inactivity threshold (default: 30 min)
- Active hours (when app is allowed to trigger)
- Quiet hours / sleep hours
- Context-aware language toggle (on/off, default off)
- Claude API key input
- AI generation frequency (daily / weekly)

### Insult Library
- Browse all messages (pre-written, AI-generated, curated)
- Filter by: level, trigger type, source, vote score
- Thumbs up/down counts on each
- Delete messages, write custom ones
- "Generate Now" button for manual AI batch

### Stats & History
- Daily/weekly/monthly step charts
- Messages received per day
- Vote history
- Escalation frequency
- "Best insults" — top voted hall of fame

### Share
- Share button on each message in the library
- Android share sheet (WhatsApp, text, social, etc.)
- Formatted as: "meatsackMotivator just called me '{insult}' 💀" with app store link

## Technical Stack

| Component | Technology |
|---|---|
| Watch app | Kotlin, Jetpack Compose for Wear OS |
| Phone app | Kotlin, Jetpack Compose (Material 3) |
| Watch-phone sync | Wear OS Data Layer API |
| Health data | Health Services API (Wear OS) + Samsung Health SDK |
| Local database | Room (SQLite) on both watch and phone |
| AI generation | Claude API (Anthropic SDK for Kotlin/Java) |
| Build system | Gradle, Android Studio |
| Min SDK | Wear OS 3.0+ (Galaxy Watch 4 and newer) |

### Project structure

```
meatsackMotivator/
├── wear/                    # Watch app module
│   ├── presentation/        # Compose UI (takeover screen, vote buttons)
│   ├── health/              # Sensor monitoring, inactivity detection
│   ├── escalation/          # Timer & level management
│   ├── messages/            # Local cache, selection algorithm
│   └── sync/                # Data Layer receive from phone
├── mobile/                  # Phone companion app module
│   ├── ui/                  # Compose UI (settings, library, stats)
│   ├── api/                 # Claude API client
│   ├── data/                # Room database, repositories
│   ├── sync/                # Data Layer send to watch
│   └── notifications/       # Optional phone-side notifications
├── shared/                  # Shared module
│   ├── models/              # Message, VoteRecord, HealthSnapshot
│   └── constants/           # Escalation levels, defaults
└── build.gradle.kts
```

## MVP Phasing

### v1 — Core insult delivery
- Watch app with inactivity detection (steps + accelerometer)
- 4-level escalation with 30-min intervals
- Screen takeover with haptic buzz
- Thumbs up/down voting and dismiss
- Pre-written message library (~100 messages to start)
- Message selection by level with no-repeat-in-24h logic
- Phone companion with: goal setting, active/quiet hours, insult library browser
- Watch-phone sync via Data Layer
- Snooze toggle on watch (1hr / 2hr / until tomorrow)

### v2 — Intelligence
- Full health data (heart rate, sleep, floors, calories, workout detection)
- Claude API integration for AI-generated messages
- Upvoted messages fed into prompt as style examples
- Context-aware language toggle
- Scheduled check-ins (hourly pace vs goal)
- End-of-day reckoning message

### v3 — Polish & social
- Positive reinforcement (grudging respect, rare genuine, streaks)
- Stats & history dashboard on phone
- Share from phone library
- Screenshot-worthy watch design with branding
- Custom user-written messages
- "Generate Now" manual AI batch button
