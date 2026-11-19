package io.github.ra5on.titanos.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestFiles.xml
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

class NoTruncatedTextDetectorTest : LintDetectorTest() {
    override fun getDetector(): Detector = NoTruncatedTextDetector()
    override fun getIssues(): List<Issue> = listOf(NoTruncatedTextDetector.ISSUE)

    private val stubs = kotlin(
        """
        package androidx.compose.ui.text.style
        class TextOverflow private constructor(val v: Int) {
            companion object { val Clip = TextOverflow(1); val Ellipsis = TextOverflow(2) }
        }
        """,
    ).indented()

    fun testEllipsisAndMaxLinesAreErrors() {
        lint().allowMissingSdk().files(
            stubs,
            kotlin(
                """
                package test
                import androidx.compose.ui.text.style.TextOverflow
                fun Text(text: String, maxLines: Int = 1, overflow: TextOverflow = TextOverflow.Clip) {}
                fun screen() {
                    Text("a", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                """,
            ).indented(),
        ).run().expectErrorCount(2)
    }

    fun testWrappingTextIsFine() {
        lint().allowMissingSdk().files(
            stubs,
            kotlin(
                """
                package test
                fun Text(text: String, softWrap: Boolean = true) {}
                fun screen() { Text("a", softWrap = true) }
                """,
            ).indented(),
        ).run().expectClean()
    }

    fun testXmlEllipsize() {
        lint().allowMissingSdk().files(
            xml(
                "res/layout/a.xml",
                """
                <TextView xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="wrap_content" android:layout_height="wrap_content"
                    android:ellipsize="end" />
                """,
            ).indented(),
        ).run().expectErrorCount(1)
    }
}
