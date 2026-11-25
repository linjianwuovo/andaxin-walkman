package com.andaxin.feature.music

/**
 * 把一个音乐页标签移到目标位置，不改变可见性或当前选中页。
 *
 * 重复、空白、不存在的标签和原地移动都返回 null，由 UI 保持原顺序而不是
 * 产生一次看似成功的无效编辑。目标索引越界时钳到列表两端，与列表按钮语义一致。
 */
internal fun moveMusicTabOrder(
    order: List<String>,
    label: String,
    toIndex: Int,
): List<String>? {
    if (order.isEmpty() || order.distinct().size != order.size) return null
    if (label.isBlank() || !order.contains(label)) return null
    val from = order.indexOf(label)
    if (from == toIndex) return null
    val destination = toIndex.coerceIn(0, order.size - 1)
    if (destination == from) return null
    return order.toMutableList().also { items -> items.removeAt(from) }.also { it.add(destination, label) }
}
