package featurecat.lizzie.teacher;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/** Common transport boundary; evidence and rendering never depend on the authentication method. */
interface CommentaryClient {
  String stream(
      List<TeacherLlmClient.Message> messages,
      TeacherLlmClient.Cancellation cancellation,
      Consumer<String> onText)
      throws IOException, InterruptedException;
}
