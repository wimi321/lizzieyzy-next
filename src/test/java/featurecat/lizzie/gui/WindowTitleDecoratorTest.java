package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WindowTitleDecoratorTest {
  @Test
  void appendsSuffixWhileActiveAndIsIdempotent() {
    String decorated = WindowTitleDecorator.decorate("LizzieYzy Next", true, "[Full Trace]");
    assertEquals("LizzieYzy Next [Full Trace]", decorated);
    assertEquals(
        decorated, WindowTitleDecorator.decorate(decorated, true, "[Full Trace]"));
  }

  @Test
  void removesSuffixWhenInactiveEvenIfPreviouslyDecorated() {
    String decorated = WindowTitleDecorator.decorate("LizzieYzy Next", true, "[Full Trace]");
    assertEquals(
        "LizzieYzy Next", WindowTitleDecorator.decorate(decorated, false, "[Full Trace]"));
  }

  @Test
  void preservesEnginePkAndWebBoardTitleStates() {
    String pk = "PK 12-3 LizzieYzy Next - kata [Web]";
    assertEquals(
        pk + " [Full Trace]", WindowTitleDecorator.decorate(pk, true, "[Full Trace]"));
    String trying = "试下中...";
    assertTrue(WindowTitleDecorator.decorate(trying, true, "[Full Trace]").endsWith("[Full Trace]"));
    assertFalse(
        WindowTitleDecorator.decorate(trying, false, "[Full Trace]").contains("[Full Trace]"));
  }
}
