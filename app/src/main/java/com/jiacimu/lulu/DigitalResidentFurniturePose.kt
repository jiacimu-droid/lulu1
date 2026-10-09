package com.jiacimu.lulu

import com.jiacimu.lulu.data.DigitalFurnitureKind
import com.jiacimu.lulu.games.WorldRectangle
import com.jiacimu.lulu.games.WorldVector

/** Only persistently recorded, still-active activities on real items drive a resident's visual pose. */
internal enum class ResidentPose { STAND, SIT, LIE }

internal object DigitalResidentFurniturePose {
    fun forActivity(activityId: String, kind: DigitalFurnitureKind): ResidentPose? = when (kind) {
        DigitalFurnitureKind.BED -> when (activityId) {
            "sit_on_bed" -> ResidentPose.SIT
            "lie_down", "rest", "nap", "sleep" -> ResidentPose.LIE
            else -> null
        }
        DigitalFurnitureKind.CHAIR -> if (activityId in setOf("sit", "rest")) ResidentPose.SIT else null
        DigitalFurnitureKind.SOFA -> if (activityId in setOf("sit", "rest", "curl_up", "nap")) ResidentPose.SIT else null
        DigitalFurnitureKind.DESK -> if (activityId == "sit_at_desk") ResidentPose.SIT else null
        DigitalFurnitureKind.TABLE, DigitalFurnitureKind.COFFEE_TABLE ->
            if (activityId == "sit_by_table") ResidentPose.SIT else null
        DigitalFurnitureKind.RUG -> when (activityId) {
            "sit_on_rug" -> ResidentPose.SIT
            "lie_on_rug" -> ResidentPose.LIE
            else -> null
        }
        else -> null
    }

    fun anchor(bounds: WorldRectangle, pose: ResidentPose): WorldVector = when (pose) {
        ResidentPose.SIT -> WorldVector(bounds.center.x, bounds.center.y + bounds.height * .12f)
        ResidentPose.LIE, ResidentPose.STAND -> bounds.center
    }
}
