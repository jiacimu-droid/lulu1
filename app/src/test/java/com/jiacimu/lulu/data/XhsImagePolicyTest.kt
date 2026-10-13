package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class XhsImagePolicyTest {
    @Test fun officialImageUrlKeepsEscapedPathAndSignature() {
        assertEquals("https://sns-img.xhscdn.com/a%2Fb?sig=x%2Fy",
            XhsImagePolicy.normalize("http://sns-img.xhscdn.com:80/a%2Fb?sig=x%2Fy"))
    }
    @Test fun unrelatedAndCredentialBearingUrlsAreRejected() {
        listOf("https://xhscdn.com.evil.test/a", "https://user:pass@sns-img.xhscdn.com/a",
            "file:///a", "/undefined", "https://sns-img.xhscdn.com:9000/a", "").forEach {
            assertNull(XhsImagePolicy.normalize(it))
        }
    }
}
