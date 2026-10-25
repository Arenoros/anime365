plugins {
    alias(libs.plugins.anime365.android.feature)
    alias(libs.plugins.anime365.hilt)
    alias(libs.plugins.roborazzi)
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.datastore)
    implementation(projects.core.model)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.annotation)
    // ReportDrawnWhen: готовая Главная для замера запуска.
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.paging.compose)

    testImplementation(libs.androidx.paging.testing)
    testImplementation(projects.core.testing)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    debugRuntimeOnly(libs.androidx.compose.ui.test.manifest)

    constraints {
        // ui-test-junit4 тянет Espresso 3.5, а она падает на SDK 36: нет InputManager.getInstance().
        testImplementation(libs.androidx.test.espresso.core)
    }
}

android {
    // Robolectric рисует экраны для скриншот-тестов (docs/roadmap.md, «Когда экран готов»).
    testOptions.unitTests.isIncludeAndroidResources = true
    // Общая песочница Robolectric протекает между классами: экран после чужих тестов снимался пустым.
    testOptions.unitTests.all { it.forkEvery = 1 }
}
