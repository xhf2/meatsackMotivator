// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.spotless)
}

subprojects {
    apply(plugin = rootProject.libs.plugins.spotless.get().pluginId)

    spotless {
        kotlin {
            target("src/**/*.kt")
            targetExclude("**/build/**/*.kt")
            ktlint(rootProject.libs.versions.ktlint.get())
                .editorConfigOverride(
                    mapOf(
                        // ktlint 1.3.1 registers the experimental MixedConditionOperatorsRule under the
                        // same id as the stable ConditionWrappingRule ("standard:condition-wrapping").
                        // Spotless collects rule providers into a randomly-ordered set, so which one wins
                        // is a per-JVM coin flip and spotlessCheck flakes. Pinning the id explicitly makes
                        // it deterministic. Fixed upstream in ktlint 1.8.0 — drop this when bumping.
                        "ktlint_standard_condition-wrapping" to "disabled",
                        "ktlint_standard_function-naming" to "disabled",
                        "ktlint_standard_property-naming" to "disabled",
                    ),
                )
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(rootProject.libs.versions.ktlint.get())
        }
    }
}
