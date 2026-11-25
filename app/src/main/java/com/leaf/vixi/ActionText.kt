package com.leaf.vixi

/**
 * 动作类消息（摸摸头之类）的人称处理。
 *
 * 同一句话要渲染两遍，人称还不一样：
 *   - 给模型看的 prompt 里，"Leaf" = 用户，"你" = 鱼
 *   - 给自己看的界面里，"你" = 用户，"她" = 鱼
 *
 * 所以存的是带占位符的模板，两边各自替换，而不是存死一种人称再硬改字符串
 * （第一版就是存了"（Leaf 伸手摸了摸你的头）"，界面只把 Leaf 换成你，
 * 结果变成"你摸了摸你的头"）。
 */
object ActionText {

    const val USER = "@USER"
    const val HER = "@HER"

    /** 摸摸头 */
    const val PAT = "（@USER 伸手摸了摸@HER的头）"

    /** 进 prompt：站在鱼视角 */
    fun forPrompt(text: String): String = text
        .replace(USER, "Leaf")
        .replace(HER, "你")
        // 兼容早期写法
        .replace("你的头", "你的头")

    /** 进界面：站在用户视角 */
    fun forUi(text: String): String = text
        .replace(USER, "你")
        .replace(HER, "她")
        // 模板里 @USER 后面带一个空格，换成"你"之后会显示成"你 伸手"，去掉它
        .replace("你 伸", "你伸")
        // 兼容早期已经存成"（Leaf 伸手摸了摸你的头）"的记录
        .replace("Leaf", "你")
        .replace("你的头", "她的头")
}
