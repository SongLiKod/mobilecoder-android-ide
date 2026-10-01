package com.mobilecoder.ide.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 关于页文案单测：声明为作者原文（法律文本），逐字锁定防止后续改动误伤。
 */
class AboutContentTest {

    @Test
    fun disclaimer_isVerbatimFourParagraphs() {
        assertEquals(
            listOf(
                "本软件为个人兴趣爱好开发产物，仅供个人学习、娱乐、非商业性质免费使用。",
                "本人对本软件享有全部合法知识产权，未经作者本人书面许可，任何单位及个人不得对本软件进行二次开发、修改、复刻、衍生创作，不得将本软件及相关资源用于商业盈利、引流变现、付费售卖等一切牟利行为，严禁任何违规商用、二次开发及非法传播行为。",
                "本软件无任何商业用途及商业服务属性，使用者在使用本软件的过程中，需自觉遵守当地法律法规及网络使用规范。因违规使用、私自篡改软件内容、非法商用、不当操作软件所造成的一切直接或间接损失、法律责任、纠纷风险等，均由使用者本人自行承担，软件作者不承担任何连带法律责任与相关后果。",
                "凡下载、安装、使用本软件，即代表本人已完整阅读、理解并自愿接受本声明全部条款。",
            ),
            AboutContent.DISCLAIMER,
        )
    }

    @Test
    fun disclaimer_isNotEmptyParagraphs() {
        assertEquals("声明应恰好四段", 4, AboutContent.DISCLAIMER.size)
        AboutContent.DISCLAIMER.forEach { paragraph ->
            assertTrue("段落不应为空白", paragraph.isNotBlank())
        }
    }

    @Test
    fun intro_describesRealFeaturesAndNotBlank() {
        val intro = AboutContent.INTRO
        assertTrue("简介不应为空", intro.isNotBlank())
        // 简介只允许陈述应用实际具备的能力（防过度承诺）
        listOf("终端", "编辑", "Git", "SSH", "构建").forEach { key ->
            assertTrue("简介应包含实际功能：$key", intro.contains(key))
        }
    }
}
