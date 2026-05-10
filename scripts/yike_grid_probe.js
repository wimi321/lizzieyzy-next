/*
 * Temporary Yike grid probe.
 *
 * Paste/run this on a Yike page, then call:
 *   __yikeGridProbe.runBoth()
 *   __yikeGridProbe.dump()
 *   __yikeGridProbe.destroy()
 *
 * Cyan dots are DOM-geometry estimates. Orange dots are canvas-pixel estimates.
 */
(function () {
  if (window.__yikeGridProbe && window.__yikeGridProbe.destroy) {
    window.__yikeGridProbe.destroy();
  }

  var OVERLAY_ID = "__yike-grid-probe-overlay";
  var LABEL_ID = "__yike-grid-probe-label";
  var BOARD_SIZE = 19;
  var RESIZE_DEBOUNCE_MS = 250;

  function round(value) {
    return Math.round(value * 100) / 100;
  }

  function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
  }

  function nodeName(el) {
    if (!el) {
      return "";
    }
    var id = el.id ? "#" + el.id : "";
    var className =
      typeof el.className === "string" && el.className.trim()
        ? "." + el.className.trim().replace(/\s+/g, ".")
        : "";
    return (el.tagName || "node").toLowerCase() + id + className;
  }

  function createOverlayCanvas() {
    var existing = document.getElementById(OVERLAY_ID);
    if (existing) {
      existing.remove();
    }
    var canvas = document.createElement("canvas");
    canvas.id = OVERLAY_ID;
    canvas.style.position = "fixed";
    canvas.style.left = "0";
    canvas.style.top = "0";
    canvas.style.width = "100vw";
    canvas.style.height = "100vh";
    canvas.style.pointerEvents = "none";
    canvas.style.zIndex = "2147483646";
    document.documentElement.appendChild(canvas);
    return canvas;
  }

  function createOverlayLabel() {
    var existing = document.getElementById(LABEL_ID);
    if (existing) {
      existing.remove();
    }
    var label = document.createElement("div");
    label.id = LABEL_ID;
    label.style.position = "fixed";
    label.style.left = "12px";
    label.style.top = "12px";
    label.style.maxWidth = "420px";
    label.style.padding = "8px 10px";
    label.style.background = "rgba(15, 23, 42, 0.85)";
    label.style.color = "#f8fafc";
    label.style.font = "12px/1.5 Consolas, 'Courier New', monospace";
    label.style.whiteSpace = "pre-wrap";
    label.style.pointerEvents = "none";
    label.style.zIndex = "2147483647";
    label.style.borderRadius = "6px";
    label.style.border = "1px solid rgba(148, 163, 184, 0.45)";
    document.documentElement.appendChild(label);
    return label;
  }

  function resizeCanvas(canvas) {
    var dpr = window.devicePixelRatio || 1;
    canvas.width = Math.round(window.innerWidth * dpr);
    canvas.height = Math.round(window.innerHeight * dpr);
    var ctx = canvas.getContext("2d");
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    return ctx;
  }

  function collectBoardCandidates() {
    var selectors = [
      "canvas",
      "svg",
      ".board_content",
      "[class*=board]",
      "[id*=board]",
      "[class*=goban]",
      "[class*=weiqi]",
    ];
    var seen = new Set();
    var out = [];

    function push(el, reason) {
      if (!el || seen.has(el)) {
        return;
      }
      seen.add(el);
      var rect = el.getBoundingClientRect();
      if (!rect || rect.width < 80 || rect.height < 80) {
        return;
      }
      var width = round(rect.width);
      var height = round(rect.height);
      var ratio = width / height;
      var score = 0;
      var name = nodeName(el);
      if (name.indexOf("canvas") === 0 || name.indexOf("svg") === 0) score += 90;
      if (name.indexOf("board_content") >= 0) score += 80;
      if (reason.indexOf("board") >= 0) score += 30;
      if (rect.left < window.innerWidth * 0.72) score += 12;
      if (rect.top < window.innerHeight * 0.9) score += 10;
      score += Math.max(0, 25 - Math.abs(width - height));
      if (ratio >= 0.8 && ratio <= 1.25) score += 18;
      out.push({
        element: el,
        node: name,
        reason: reason,
        score: score,
        rect: {
          left: round(rect.left),
          top: round(rect.top),
          width: width,
          height: height,
          right: round(rect.right),
          bottom: round(rect.bottom),
        },
      });
    }

    selectors.forEach(function (selector) {
      try {
        document.querySelectorAll(selector).forEach(function (el) {
          push(el, "selector:" + selector);
        });
      } catch (error) {}
    });

    out.sort(function (a, b) {
      if (b.score !== a.score) {
        return b.score - a.score;
      }
      return b.rect.width * b.rect.height - a.rect.width * a.rect.height;
    });
    return out.slice(0, 8);
  }

  function getContentBoxRect(el) {
    var rect = el.getBoundingClientRect();
    var style = getComputedStyle(el);
    var left = rect.left + parseFloat(style.borderLeftWidth || 0) + parseFloat(style.paddingLeft || 0);
    var top = rect.top + parseFloat(style.borderTopWidth || 0) + parseFloat(style.paddingTop || 0);
    var right = rect.right - parseFloat(style.borderRightWidth || 0) - parseFloat(style.paddingRight || 0);
    var bottom = rect.bottom - parseFloat(style.borderBottomWidth || 0) - parseFloat(style.paddingBottom || 0);
    return {
      left: round(left),
      top: round(top),
      right: round(right),
      bottom: round(bottom),
      width: round(right - left),
      height: round(bottom - top),
    };
  }

  function solveDomGrid(candidate) {
    if (!candidate) {
      return null;
    }
    var rect = candidate.rect;
    var sourceRect = rect;
    if (candidate.node.indexOf("board_content") >= 0) {
      sourceRect = getContentBoxRect(candidate.element);
    }

    var insetX = sourceRect.width * 0.055;
    var insetY = sourceRect.height * 0.058;
    var firstLineX = sourceRect.left + insetX;
    var lastLineX = sourceRect.right - insetX;
    var firstLineY = sourceRect.top + insetY;
    var lastLineY = sourceRect.bottom - insetY;
    var cellX = (lastLineX - firstLineX) / (BOARD_SIZE - 1);
    var cellY = (lastLineY - firstLineY) / (BOARD_SIZE - 1);

    return {
      mode: "dom",
      candidateNode: candidate.node,
      candidateReason: candidate.reason,
      candidateRect: sourceRect,
      boardSize: BOARD_SIZE,
      firstLineX: round(firstLineX),
      firstLineY: round(firstLineY),
      lastLineX: round(lastLineX),
      lastLineY: round(lastLineY),
      cellX: round(cellX),
      cellY: round(cellY),
      confidence: candidate.score,
    };
  }

  function findReadableCanvas(candidate) {
    if (candidate && candidate.element) {
      var tagName = (candidate.element.tagName || "").toLowerCase();
      if (tagName === "canvas") {
        return candidate.element;
      }
      if (candidate.element.querySelector) {
        var nested = candidate.element.querySelector("canvas");
        if (nested) {
          return nested;
        }
      }
    }
    return document.querySelector("canvas");
  }

  function extractImageData(candidate, rect) {
    var target = document.createElement("canvas");
    target.width = Math.max(1, Math.round(rect.width));
    target.height = Math.max(1, Math.round(rect.height));
    var ctx = target.getContext("2d", { willReadFrequently: true });

    try {
      var canvas = findReadableCanvas(candidate);
      if (canvas) {
        var canvasRect = canvas.getBoundingClientRect();
        var sx = ((rect.left - canvasRect.left) / canvasRect.width) * canvas.width;
        var sy = ((rect.top - canvasRect.top) / canvasRect.height) * canvas.height;
        var sw = (rect.width / canvasRect.width) * canvas.width;
        var sh = (rect.height / canvasRect.height) * canvas.height;
        ctx.drawImage(canvas, sx, sy, sw, sh, 0, 0, target.width, target.height);
        return ctx.getImageData(0, 0, target.width, target.height);
      }
    } catch (error) {}

    return null;
  }

  function scanLineStrength(imageData, axis) {
    var width = imageData.width;
    var height = imageData.height;
    var data = imageData.data;
    var size = axis === "x" ? width : height;
    var otherSize = axis === "x" ? height : width;
    var values = new Array(size);
    for (var i = 0; i < size; i++) {
      var total = 0;
      for (var j = 0; j < otherSize; j++) {
        var x = axis === "x" ? i : j;
        var y = axis === "x" ? j : i;
        var offset = (y * width + x) * 4;
        total += data[offset] + data[offset + 1] + data[offset + 2];
      }
      values[i] = total / otherSize;
    }
    return values;
  }

  function detectLineFamily(values, expectedCount) {
    if (!values || values.length < expectedCount) {
      return null;
    }
    var min = Infinity;
    var max = -Infinity;
    for (var i = 0; i < values.length; i++) {
      min = Math.min(min, values[i]);
      max = Math.max(max, values[i]);
    }
    var threshold = min + (max - min) * 0.32;
    var points = [];
    for (var index = 1; index < values.length - 1; index++) {
      if (values[index] <= threshold && values[index] <= values[index - 1] && values[index] <= values[index + 1]) {
        points.push(index);
      }
    }
    if (points.length < expectedCount) {
      return null;
    }
    points.sort(function (a, b) {
      return a - b;
    });
    var reduced = [points[0]];
    for (var k = 1; k < points.length; k++) {
      if (points[k] - reduced[reduced.length - 1] >= 4) {
        reduced.push(points[k]);
      }
    }
    if (reduced.length < expectedCount) {
      return null;
    }
    var sampled = [];
    var step = (reduced.length - 1) / (expectedCount - 1);
    for (var s = 0; s < expectedCount; s++) {
      sampled.push(reduced[Math.round(step * s)]);
    }
    return sampled;
  }

  function solvePixelGrid(candidate) {
    if (!candidate) {
      return null;
    }
    var sourceRect = candidate.node.indexOf("board_content") >= 0 ? getContentBoxRect(candidate.element) : candidate.rect;
    var imageData = extractImageData(candidate, sourceRect);
    if (!imageData) {
      return null;
    }
    var xLines = detectLineFamily(scanLineStrength(imageData, "x"), BOARD_SIZE);
    var yLines = detectLineFamily(scanLineStrength(imageData, "y"), BOARD_SIZE);
    if (!xLines || !yLines) {
      return null;
    }

    var firstLineX = sourceRect.left + xLines[0];
    var lastLineX = sourceRect.left + xLines[xLines.length - 1];
    var firstLineY = sourceRect.top + yLines[0];
    var lastLineY = sourceRect.top + yLines[yLines.length - 1];

    return {
      mode: "pixel",
      candidateNode: candidate.node,
      candidateReason: candidate.reason,
      candidateRect: sourceRect,
      boardSize: BOARD_SIZE,
      firstLineX: round(firstLineX),
      firstLineY: round(firstLineY),
      lastLineX: round(lastLineX),
      lastLineY: round(lastLineY),
      cellX: round((lastLineX - firstLineX) / (BOARD_SIZE - 1)),
      cellY: round((lastLineY - firstLineY) / (BOARD_SIZE - 1)),
      confidence: clamp(candidate.score + 10, 0, 200),
    };
  }

  function drawPoints(ctx, result, color) {
    if (!result) {
      return;
    }
    ctx.save();
    ctx.strokeStyle = color;
    ctx.fillStyle = color;
    for (var x = 0; x < BOARD_SIZE; x++) {
      for (var y = 0; y < BOARD_SIZE; y++) {
        var px = result.firstLineX + result.cellX * x;
        var py = result.firstLineY + result.cellY * y;
        var radius = 2;
        var isCorner = (x === 0 || x === BOARD_SIZE - 1) && (y === 0 || y === BOARD_SIZE - 1);
        var isCenter = x === 9 && y === 9;
        if (isCorner) {
          radius = 4;
        } else if (isCenter) {
          radius = 5;
        }
        ctx.beginPath();
        ctx.arc(px, py, radius, 0, Math.PI * 2);
        ctx.fill();
        if (isCenter) {
          ctx.beginPath();
          ctx.moveTo(px - 7, py);
          ctx.lineTo(px + 7, py);
          ctx.moveTo(px, py - 7);
          ctx.lineTo(px, py + 7);
          ctx.stroke();
        }
      }
    }
    ctx.restore();
  }

  function formatResult(result) {
    if (!result) {
      return "no result";
    }
    return [
      "mode: " + result.mode,
      "candidate: " + result.candidateNode,
      "reason: " + result.candidateReason,
      "rect: " +
        [result.candidateRect.left, result.candidateRect.top, result.candidateRect.width, result.candidateRect.height].join(","),
      "first: " + [result.firstLineX, result.firstLineY].join(","),
      "last: " + [result.lastLineX, result.lastLineY].join(","),
      "cell: " + [result.cellX, result.cellY].join(","),
      "confidence: " + result.confidence,
    ].join("\n");
  }

  var state = {
    overlayCanvas: createOverlayCanvas(),
    overlayLabel: createOverlayLabel(),
    results: {},
    resizeTimer: 0,
  };

  function render(results) {
    var ctx = resizeCanvas(state.overlayCanvas);
    ctx.clearRect(0, 0, window.innerWidth, window.innerHeight);
    if (results.dom) {
      drawPoints(ctx, results.dom, "rgba(34, 211, 238, 0.92)");
    }
    if (results.pixel) {
      drawPoints(ctx, results.pixel, "rgba(249, 115, 22, 0.92)");
    }
    state.overlayLabel.textContent = Object.keys(results)
      .map(function (key) {
        return "[" + key.toUpperCase() + "]\n" + formatResult(results[key]);
      })
      .join("\n\n");
  }

  function runProbe(mode) {
    var candidates = collectBoardCandidates();
    var candidate = candidates.length > 0 ? candidates[0] : null;
    var results = {};
    if (mode === "dom" || mode === "both") {
      results.dom = solveDomGrid(candidate);
    }
    if (mode === "pixel" || mode === "both") {
      results.pixel = solvePixelGrid(candidate);
    }
    state.results = results;
    window.__yikeGridProbe.lastCandidates = candidates;
    window.__yikeGridProbe.lastResults = results;
    render(results);
    var output = {
      candidates: candidates.map(function (item) {
        return {
          node: item.node,
          reason: item.reason,
          score: item.score,
          rect: item.rect,
        };
      }),
      results: results,
    };
    if (console.table) {
      console.table(output.candidates);
    }
    console.log("[yikeGridProbe] results", output.results);
    return output;
  }

  function clear() {
    state.results = {};
    resizeCanvas(state.overlayCanvas).clearRect(0, 0, window.innerWidth, window.innerHeight);
    state.overlayLabel.textContent = "";
  }

  function destroy() {
    if (state.resizeTimer) {
      clearTimeout(state.resizeTimer);
      state.resizeTimer = 0;
    }
    window.removeEventListener("resize", onResize, { passive: true });
    state.overlayCanvas.remove();
    state.overlayLabel.remove();
    delete window.__yikeGridProbe;
  }

  function onResize() {
    if (state.resizeTimer) {
      clearTimeout(state.resizeTimer);
    }
    state.resizeTimer = setTimeout(function () {
      state.resizeTimer = 0;
      if (window.__yikeGridProbe && window.__yikeGridProbe.lastMode) {
        runProbe(window.__yikeGridProbe.lastMode);
      } else {
        clear();
      }
    }, RESIZE_DEBOUNCE_MS);
  }

  window.addEventListener("resize", onResize, { passive: true });

  window.__yikeGridProbe = {
    runDom: function () {
      this.lastMode = "dom";
      return runProbe("dom");
    },
    runPixel: function () {
      this.lastMode = "pixel";
      return runProbe("pixel");
    },
    runBoth: function () {
      this.lastMode = "both";
      return runProbe("both");
    },
    clear: clear,
    destroy: destroy,
    dump: function () {
      return {
        candidates: this.lastCandidates || [],
        results: this.lastResults || {},
      };
    },
    lastCandidates: [],
    lastResults: {},
    lastMode: "",
  };

  console.log("[yikeGridProbe] ready", {
    usage: [
      "__yikeGridProbe.runDom()",
      "__yikeGridProbe.runPixel()",
      "__yikeGridProbe.runBoth()",
      "__yikeGridProbe.dump()",
      "__yikeGridProbe.clear()",
      "__yikeGridProbe.destroy()",
    ],
  });
})();
