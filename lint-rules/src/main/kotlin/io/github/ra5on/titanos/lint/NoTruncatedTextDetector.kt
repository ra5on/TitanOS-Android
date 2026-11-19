package io.github.ra5on.titanos.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.android.tools.lint.detector.api.XmlContext
import com.android.tools.lint.detector.api.XmlScanner
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.w3c.dom.Attr
import java.util.EnumSet

/**
 * TitanOS: Text wird nie abgeschnitten (kein „…“, keine Zeilenbegrenzung).
 * Verboten sind `TextOverflow.Ellipsis`, das Argument `maxLines` und in XML
 * `android:maxLines`, `android:ellipsize` und `android:singleLine`.
 */
class NoTruncatedTextDetector : Detector(), SourceCodeScanner, XmlScanner {

    override fun getApplicableUastTypes(): List<Class<out UElement>> =
        listOf(UQualifiedReferenceExpression::class.java, UCallExpression::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler = object : UElementHandler() {
        override fun visitQualifiedReferenceExpression(node: UQualifiedReferenceExpression) {
            val text = node.sourcePsi?.text?.replace(Regex("\\s"), "") ?: return
            if (text == "TextOverflow.Ellipsis" || text.endsWith(".TextOverflow.Ellipsis")) {
                context.report(ISSUE, node, context.getLocation(node), MESSAGE)
            }
        }

        override fun visitCallExpression(node: UCallExpression) {
            for (arg in node.valueArguments) {
                val ktArg = arg.sourcePsi?.parent as? KtValueArgument ?: continue
                if (ktArg.getArgumentName()?.asName?.asString() == "maxLines") {
                    context.report(ISSUE, node, context.getLocation(ktArg), MESSAGE)
                }
            }
        }
    }

    override fun getApplicableAttributes(): Collection<String> = listOf("maxLines", "ellipsize", "singleLine", "lines")

    override fun visitAttribute(context: XmlContext, attribute: Attr) {
        context.report(ISSUE, attribute, context.getLocation(attribute), MESSAGE)
    }

    companion object {
        private const val MESSAGE =
            "Kein abgeschnittener Text: umbrechen oder scrollen statt Ellipsis oder Zeilenbegrenzung."

        val ISSUE: Issue = Issue.create(
            id = "TitanNoTruncatedText",
            briefDescription = "Abgeschnittener Text",
            explanation = "In TitanOS darf kein Text abgeschnitten werden (CLAUDE.md, Oberfläche). " +
                "TextOverflow.Ellipsis und maxLines verstecken Text; lass ihn umbrechen oder scrollen.",
            category = Category.USABILITY,
            priority = 8,
            severity = Severity.ERROR,
            implementation = Implementation(
                NoTruncatedTextDetector::class.java,
                EnumSet.of(Scope.JAVA_FILE, Scope.RESOURCE_FILE),
                EnumSet.of(Scope.JAVA_FILE),
                EnumSet.of(Scope.RESOURCE_FILE),
            ),
        )
    }
}
