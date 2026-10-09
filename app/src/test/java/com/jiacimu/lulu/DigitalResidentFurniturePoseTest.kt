package com.jiacimu.lulu

import com.jiacimu.lulu.data.DigitalFurnitureKind
import com.jiacimu.lulu.games.WorldRectangle
import org.junit.Assert.*
import org.junit.Test

class DigitalResidentFurniturePoseTest {
    @Test fun verifiedChairRestLooksSeatedAndBedRestLooksReclining() {
        assertEquals(ResidentPose.SIT, DigitalResidentFurniturePose.forActivity("rest", DigitalFurnitureKind.CHAIR))
        assertEquals(ResidentPose.LIE, DigitalResidentFurniturePose.forActivity("rest", DigitalFurnitureKind.BED))
        assertEquals(ResidentPose.LIE, DigitalResidentFurniturePose.forActivity("sleep", DigitalFurnitureKind.BED))
        assertEquals(ResidentPose.SIT, DigitalResidentFurniturePose.forActivity("sit_on_bed", DigitalFurnitureKind.BED))
    }
    @Test fun rejectIncompatibleFurnitureInsteadOfFakingActivity() {
        assertNull(DigitalResidentFurniturePose.forActivity("nap", DigitalFurnitureKind.CHAIR))
        assertNull(DigitalResidentFurniturePose.forActivity("sit", DigitalFurnitureKind.PLANT))
        assertNull(DigitalResidentFurniturePose.forActivity("sleep", DigitalFurnitureKind.CHAIR))
    }
    @Test fun anchorIsFromActualFurnitureBounds() {
        val box = WorldRectangle(100f, 300f, 260f, 440f)
        assertEquals(180f, DigitalResidentFurniturePose.anchor(box, ResidentPose.LIE).x, .001f)
        assertTrue(DigitalResidentFurniturePose.anchor(box, ResidentPose.SIT).y > box.center.y)
    }
}
