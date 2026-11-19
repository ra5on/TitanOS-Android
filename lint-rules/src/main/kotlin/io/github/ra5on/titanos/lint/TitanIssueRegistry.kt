package io.github.ra5on.titanos.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

class TitanIssueRegistry : IssueRegistry() {
    override val issues: List<Issue> = listOf(NoTruncatedTextDetector.ISSUE)
    override val api: Int = CURRENT_API
    override val minApi: Int = 14
    override val vendor: Vendor = Vendor(vendorName = "TitanOS", identifier = "titanos-lint")
}
