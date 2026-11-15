package com.andaxin.shell

/*
 * Swift 侧唯一要读的那点东西。
 * 用 object 是为了在 Swift 里有确定的形状（IosShell.shared.xxx()），
 * 顶层的自由函数在 K/N 的 ObjC 导出里会落进 <File>Kt 那个类，名字随文件名走，不好钉。
 */
object IosShell {
    fun title(): String = "安大信"

    fun subtitle(): String = "随身听 · iOS 壳（第一版）"

    fun version(): String = "0.9.28"

    fun note(): String = "这条包只证明 iOS 出包链路通了；界面还是安卓那套，没搬过来。"
}
