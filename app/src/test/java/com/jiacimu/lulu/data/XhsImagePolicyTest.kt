package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import com.jiacimu.lulu.ai.VisionModelService

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

    @Test fun sixIllustrationsShareOneSafeTransportByteBudget() {
        assertEquals(0, VisionModelService.xhsImageByteBudget(0))
        assertEquals(240_000, VisionModelService.xhsImageByteBudget(1))
        assertEquals(180_000, VisionModelService.xhsImageByteBudget(2))
        assertEquals(120_000, VisionModelService.xhsImageByteBudget(3))
        assertEquals(60_000, VisionModelService.xhsImageByteBudget(6))
        for (count in 1..6) {
            val jpegBudget = VisionModelService.xhsImageByteBudget(count) * count
            assertTrue("Raw JPEGs must fit one shared POST budget", jpegBudget <= 360_000)
            val base64UpperBound = ((jpegBudget + 2) / 3) * 4
            assertTrue("Encoded images must fit a bounded relay body", base64UpperBound <= 480_000)
        }
    }
}
