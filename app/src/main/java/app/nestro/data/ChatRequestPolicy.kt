package app.nestro.data
import java.io.IOException
data class ChatHistoryItem(val role:String,val content:String)
object ChatRequestPolicy {
 const val MAX_HISTORY_CHARS=48000
 fun estimateTokens(text:String):Int=((text.length+3)/4).coerceAtLeast(if(text.isBlank())0 else 1)
 fun boundedHistory(history:List<ChatHistoryItem>,systemPrompt:String,contextWindowTokens:Int=16384,outputReserveTokens:Int=2048):List<ChatHistoryItem>{
  val available=(contextWindowTokens.coerceAtLeast(2048)-estimateTokens(systemPrompt)-outputReserveTokens.coerceAtLeast(256)).coerceAtLeast(512)
  var remaining=minOf(MAX_HISTORY_CHARS,available*4);val out=ArrayList<ChatHistoryItem>()
  for(m in history.asReversed().take(40)){if(remaining<=0)break;val c=if(m.content.length>remaining)m.content.takeLast(remaining) else m.content;out+=m.copy(content=c);remaining-=c.length}
  return out.asReversed()
 }
 fun shouldRetry(error:Throwable):Boolean{
  var e:Throwable?=error
  repeat(5){val x=e?:return false;if(x is kotlinx.coroutines.CancellationException)return false;if(x is IOException)return true
   val m=x.message.orEmpty()
   if(Regex("""HTTP\s+(408|425|429|5\d\d)""",RegexOption.IGNORE_CASE).containsMatchIn(m))return true
   if(m.contains("timeout",true)||m.contains("timed out",true)||m.contains("connection reset",true)||m.contains("连接重置")||m.contains("unexpected end",true))return true
   e=x.cause
  };return false
 }
}
