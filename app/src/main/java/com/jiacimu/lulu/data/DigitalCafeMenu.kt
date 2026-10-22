package com.jiacimu.lulu.data

/**
 * Shared menu for existing digital world executor and body simulation.
 * Picking up an item and consuming it are separate persisted actions.
 */
internal data class DigitalCafeOffering(
    val id: String,
    val name: String,
    val thirstRelief: Double = 0.0,
    val hungerRelief: Double = 0.0,
    val comfortGain: Double = 0.0,
) {
    val orderActivity: String get() = "cafe_order_$id"
    val consumeActivity: String get() = "cafe_consume_$id"
    val inventoryPrefix: String get() = "cafe:item:$id:"
    val verb: String get() = if (thirstRelief > hungerRelief) "喝完" else "吃完"
}

internal object DigitalCafeMenu {
    val offerings = listOf(
        DigitalCafeOffering("coffee", "热咖啡", thirstRelief = 17.0, comfortGain = 4.0),
        DigitalCafeOffering("tea", "花茶", thirstRelief = 23.0, comfortGain = 4.0),
        DigitalCafeOffering("cocoa", "热可可", thirstRelief = 18.0, hungerRelief = 7.0, comfortGain = 5.0),
        DigitalCafeOffering("sandwich", "三明治", hungerRelief = 34.0, comfortGain = 3.0),
        DigitalCafeOffering("cake", "小蛋糕", hungerRelief = 17.0, comfortGain = 5.0),
    )
    val orderActionIds = offerings.map { it.orderActivity }.toSet()
    val consumeActionIds = offerings.map { it.consumeActivity }.toSet()
    fun byOrder(action: String): DigitalCafeOffering? = offerings.firstOrNull { it.orderActivity == action }
    fun byConsume(action: String): DigitalCafeOffering? = offerings.firstOrNull { it.consumeActivity == action }
    fun locationOptions(): List<Pair<String, String>> = offerings.flatMap { item ->
        listOf(item.orderActivity to "点一份${item.name}", item.consumeActivity to "${item.verb}一份${item.name}")
    }
    fun names(): String = offerings.joinToString("、") { it.name }
}
