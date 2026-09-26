package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
import com.example.data.model.MessageRole
import com.example.data.preferences.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("AI Chatbot", appName)
  }

  @Test
  fun `verify chat message creation`() {
    val msg = ChatMessage(
      conversationId = "conv-1",
      role = MessageRole.USER,
      content = "I need a marketing campaign idea"
    )
    assertEquals("conv-1", msg.conversationId)
    assertEquals(MessageRole.USER, msg.role)
    assertEquals("I need a marketing campaign idea", msg.content)
  }

  @Test
  fun `verify conversation creation`() {
    val conv = Conversation(
      title = "Productivity App Campaign",
      modelName = "deepseek-ai/deepseek-v4.1-flash"
    )
    assertNotNull(conv.id)
    assertEquals("Productivity App Campaign", conv.title)
    assertEquals("deepseek-ai/deepseek-v4.1-flash", conv.modelName)
  }

  @Test
  fun `verify settings manager defaults`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val settings = SettingsManager(context)
    assertEquals("", settings.getUserName())
    assertEquals("Free plan", settings.getPlanType())
    assertEquals(SettingsManager.DEFAULT_SYSTEM_PROMPT, settings.getSystemPrompt())
    assertEquals(SettingsManager.DEFAULT_TEMPERATURE, settings.getTemperature())
    assertEquals(SettingsManager.DEFAULT_TOP_P, settings.getTopP())
    assertEquals(SettingsManager.DEFAULT_MAX_TOKENS, settings.getMaxTokens())
  }

  @Test
  fun `verify hyperparameters persistence`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val settings = SettingsManager(context)
    settings.setSystemPrompt("Custom expert prompt")
    settings.setTemperature(0.4f)
    settings.setTopP(0.85f)
    settings.setMaxTokens(2048)

    assertEquals("Custom expert prompt", settings.getSystemPrompt())
    assertEquals(0.4f, settings.getTemperature(), 0.01f)
    assertEquals(0.85f, settings.getTopP(), 0.01f)
    assertEquals(2048, settings.getMaxTokens())
  }

  @Test
  fun `verify available models catalog contains nvidia build endpoints`() {
    val models = com.example.ui.components.AvailableModels
    assertTrue(models.isNotEmpty())
    assertTrue(models.any { it.id == "meta/llama-3.3-70b-instruct" })
    assertTrue(models.any { it.id == "deepseek-ai/deepseek-r1" })
    assertTrue(models.any { it.id == "qwen/qwen2.5-coder-32b-instruct" })
    assertTrue(models.any { it.id == "nvidia/llama-3.1-nemotron-70b-instruct" })
  }

  @Test
  fun `verify rag retrieval ranking`() {
    val doc = com.example.data.rag.RagDocument(
      id = "doc-1",
      name = "tech_spec.txt",
      sizeBytes = 100L,
      fullText = "Kotlin coroutines provide structured concurrency and Flow handles asynchronous streams.",
      chunks = listOf(
        com.example.data.rag.RagChunk(
          documentId = "doc-1",
          documentName = "tech_spec.txt",
          index = 0,
          text = "Kotlin coroutines provide structured concurrency and Flow handles asynchronous data streams.",
          wordCount = 12,
          termFrequencies = mapOf("kotlin" to 1, "coroutines" to 1, "concurrency" to 1, "flow" to 1)
        )
      )
    )

    val results = com.example.data.rag.RagEngine.retrieveRelevantContext("coroutines concurrency", doc, topK = 1)
    assertTrue(results.isNotEmpty())
    assertEquals(0, results[0].chunk.index)
  }
}
