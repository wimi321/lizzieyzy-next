(function () {
  "use strict";

  // ---------------------------------------------------------------------------
  // State
  // ---------------------------------------------------------------------------
  var boardState = null;
  var analysisData = null;
  var winrateHistory = null;
  var hoveredMove = null;
  // 用 localStorage 持久化用户偏好。读不到 / 解析失败时取默认。
  var prefsKey = "lizzie-web-prefs-v1";
  var PREF_DEFAULTS = {
    heatmapEnabled: false,
    viewAsBlack: true,
    coordStyle: "off",
    showCiWinrate: true,
    showCiPlayouts: true,
    showCiScore: true,
  };
  function loadPrefs() {
    try {
      var raw = localStorage.getItem(prefsKey);
      if (!raw) return {};
      return JSON.parse(raw) || {};
    } catch (err) {
      return {};
    }
  }
  function savePrefs() {
    try {
      localStorage.setItem(prefsKey, JSON.stringify({
        heatmapEnabled: heatmapEnabled,
        viewAsBlack: viewAsBlack,
        coordStyle: coordStyle,
        showCiWinrate: showCiWinrate,
        showCiPlayouts: showCiPlayouts,
        showCiScore: showCiScore,
      }));
    } catch (err) {}
  }
  function prefOr(prefs, key) {
    return prefs[key] !== undefined ? prefs[key] : PREF_DEFAULTS[key];
  }
  var __prefs = loadPrefs();
  var heatmapEnabled = prefOr(__prefs, "heatmapEnabled");
  var viewAsBlack = prefOr(__prefs, "viewAsBlack");
  var coordStyle = prefOr(__prefs, "coordStyle");
  var chartHoverIdx = -1;
  var showCiWinrate = prefOr(__prefs, "showCiWinrate");
  var showCiPlayouts = prefOr(__prefs, "showCiPlayouts");
  var showCiScore = prefOr(__prefs, "showCiScore");
  var ws = null;
  var reconnectDelay = 1000;
  var longPressTimer = null;

  var clientId = (function () {
    var id = null;
    try { id = localStorage.getItem("webBoardClientId"); } catch (_) {}
    if (!id) {
      if (window.crypto && window.crypto.randomUUID) {
        id = window.crypto.randomUUID();
      } else {
        // 退化：随机 16 字节十六进制
        var arr = new Uint8Array(16);
        (window.crypto || window.msCrypto).getRandomValues(arr);
        id = Array.from(arr).map(function (b) { return ("0" + b.toString(16)).slice(-2); }).join("");
      }
      try { localStorage.setItem("webBoardClientId", id); } catch (_) {}
    }
    return id;
  })();

  var trialState = null;          // 最新一次 trial_state 消息（null = idle）
  var siblingMarkers = null;       // 当前试下分叉处的非主线子节点位置

  // DOM references
  var boardCanvas = document.getElementById("board-canvas");
  var boardCtx = boardCanvas.getContext("2d");
  var chartCanvas = document.getElementById("winrate-chart");
  var chartCtx = chartCanvas.getContext("2d");
  var scoreCanvas = document.getElementById("score-chart");
  var scoreCtx = scoreCanvas.getContext("2d");
  var overlay = document.getElementById("connection-overlay");
  var chartTooltip = document.getElementById("chart-tooltip");

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------
  function formatPlayouts(n) {
    if (n == null) return "-";
    if (n >= 1000000) return (n / 1000000).toFixed(1) + "M";
    if (n >= 1000) return (n / 1000).toFixed(1) + "k";
    return String(n);
  }

  /**
   * Parse a GTP coordinate string (e.g. "Q16") to [x, y] grid indices.
   * Column letters: A=0, B=1, ..., H=7, J=8 (I is skipped). Row is 1-based
   * from the bottom.
   */
  function gtpToXY(coord, boardHeight) {
    if (!coord || coord.length < 2) return null;
    var col = coord.charCodeAt(0);
    // uppercase
    if (col >= 97) col -= 32;
    var x = col - 65; // A=0
    if (x > 7) x--; // skip I
    var row = parseInt(coord.substring(1), 10);
    if (isNaN(row)) return null;
    var y = boardHeight - row;
    return [x, y];
  }

  // ---------------------------------------------------------------------------
  // WebSocket
  // ---------------------------------------------------------------------------
  function connectWs() {
    var port =
      typeof window.WS_PORT === "number" && !isNaN(window.WS_PORT)
        ? window.WS_PORT
        : parseInt(location.port, 10) + 1;
    var url = "ws://" + location.hostname + ":" + port;

    ws = new WebSocket(url);

    ws.onopen = function () {
      reconnectDelay = 1000;
      overlay.style.display = "none";
    };

    ws.onmessage = function (evt) {
      var msg;
      try {
        msg = JSON.parse(evt.data);
      } catch (_) {
        return;
      }

      switch (msg.type) {
        case "full_state":
          boardState = msg;
          analysisData = msg;
          render();
          break;
        case "analysis_update":
          analysisData = msg;
          if (boardState) {
            if (msg.bestMoves) boardState.bestMoves = msg.bestMoves;
            if (msg.estimateArray) boardState.estimateArray = msg.estimateArray;
            if (msg.winrate != null) boardState.winrate = msg.winrate;
            if (msg.scoreMean != null) boardState.scoreMean = msg.scoreMean;
            if (msg.playouts != null) boardState.playouts = msg.playouts;
          }
          render();
          break;
        case "winrate_history":
          winrateHistory = msg.data;
          renderWinrateChart();
          renderScoreChart();
          renderBlunderList();
          break;
        case "trial_state":
          trialState = msg.active ? msg : null;
          siblingMarkers = (msg.active && msg.siblingMarkers) ? msg.siblingMarkers : null;
          applyTrialUiState();
          render();  // 重画棋盘以反映 sibling markers 变化
          break;
        case "trial_denied":
          // 简单 alert 即可（spec 没要求 toast）
          alert("另一位用户正在试下中，稍后再试");
          break;
      }
    };

    ws.onclose = function () {
      overlay.style.display = "flex";
      scheduleReconnect();
    };

    ws.onerror = function () {
      try {
        ws.close();
      } catch (_) {
        /* ignore */
      }
    };
  }

  function scheduleReconnect() {
    setTimeout(function () {
      reconnectDelay = Math.min(reconnectDelay * 2, 30000);
      connectWs();
    }, reconnectDelay);
  }

  // ---------------------------------------------------------------------------
  // Rendering – main entry
  // ---------------------------------------------------------------------------
  function render() {
    if (!boardState) return;

    var container = document.getElementById("board-container");
    var size = Math.min(container.clientWidth, container.clientHeight);
    if (size <= 0) return;

    var dpr = window.devicePixelRatio || 1;
    boardCanvas.width = size * dpr;
    boardCanvas.height = size * dpr;
    boardCanvas.style.width = size + "px";
    boardCanvas.style.height = size + "px";
    boardCtx.setTransform(dpr, 0, 0, dpr, 0, 0);

    var boardWidth = boardState.boardWidth || 19;
    var boardHeight = boardState.boardHeight || 19;
    var margin = coordStyle !== "off" ? size * 0.06 : size * 0.04;
    var gridSize = (size - 2 * margin) / (Math.max(boardWidth, boardHeight) - 1);

    drawBoard(boardCtx, size, boardWidth, boardHeight, margin, gridSize);
    if (coordStyle !== "off") {
      drawCoordinates(boardCtx, boardWidth, boardHeight, margin, gridSize);
    }
    drawStones(boardCtx, boardState, boardWidth, boardHeight, margin, gridSize);
    if (heatmapEnabled && boardState.estimateArray) {
      drawHeatmap(boardCtx, boardState.estimateArray, boardWidth, boardHeight, margin, gridSize);
    }
    if (boardState.bestMoves) {
      drawCandidates(boardCtx, boardState.bestMoves, boardHeight, margin, gridSize);
    }
    if (hoveredMove && hoveredMove.variation) {
      drawVariation(
        boardCtx,
        hoveredMove.variation,
        boardState.currentPlayer,
        boardHeight,
        margin,
        gridSize
      );
    }
    if (siblingMarkers && siblingMarkers.length > 0) {
      drawSiblingMarkers(boardCtx, siblingMarkers, boardHeight, margin, gridSize);
    }

    updateStatusBar();
  }

  // ---------------------------------------------------------------------------
  // drawBoard
  // ---------------------------------------------------------------------------
  function drawBoard(ctx, size, boardWidth, boardHeight, margin, gridSize) {
    // Background
    ctx.fillStyle = "#c8a45c";
    ctx.fillRect(0, 0, size, size);

    // Grid lines
    ctx.strokeStyle = "#8B7355";
    ctx.lineWidth = 1;

    for (var x = 0; x < boardWidth; x++) {
      var px = margin + x * gridSize;
      ctx.beginPath();
      ctx.moveTo(px, margin);
      ctx.lineTo(px, margin + (boardHeight - 1) * gridSize);
      ctx.stroke();
    }
    for (var y = 0; y < boardHeight; y++) {
      var py = margin + y * gridSize;
      ctx.beginPath();
      ctx.moveTo(margin, py);
      ctx.lineTo(margin + (boardWidth - 1) * gridSize, py);
      ctx.stroke();
    }

    // Star points (hoshi)
    var stars;
    if (boardWidth === 19 && boardHeight === 19) {
      stars = [
        [3, 3], [3, 9], [3, 15],
        [9, 3], [9, 9], [9, 15],
        [15, 3], [15, 9], [15, 15]
      ];
    } else if (boardWidth === 13 && boardHeight === 13) {
      stars = [[3, 3], [3, 9], [6, 6], [9, 3], [9, 9]];
    } else if (boardWidth === 9 && boardHeight === 9) {
      stars = [[2, 2], [2, 6], [4, 4], [6, 2], [6, 6]];
    }
    if (stars) {
      ctx.fillStyle = "#8B7355";
      for (var i = 0; i < stars.length; i++) {
        var sx = margin + stars[i][0] * gridSize;
        var sy = margin + stars[i][1] * gridSize;
        ctx.beginPath();
        ctx.arc(sx, sy, gridSize * 0.12, 0, Math.PI * 2);
        ctx.fill();
      }
    }
  }

  var COLS_SKIP_I = "ABCDEFGHJKLMNOPQRST";
  var COLS_WITH_I = "ABCDEFGHIJKLMNOPQRS";

  function getColLabel(x) {
    // 野狐 / withI 都用含 I 的列字母；GTP 标准（off / 默认）跳过 I。
    if (coordStyle === "withI" || coordStyle === "fox") return COLS_WITH_I.charAt(x);
    return COLS_SKIP_I.charAt(x);
  }

  function getRowLabel(y, boardHeight) {
    if (coordStyle === "fox") return String(y + 1);
    return String(boardHeight - y);
  }

  function drawCoordinates(ctx, boardWidth, boardHeight, margin, gridSize) {
    ctx.fillStyle = "#5a4a3a";
    ctx.font = "bold " + Math.max(9, gridSize * 0.30) + "px sans-serif";
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";

    var offset = margin * 0.55;

    for (var x = 0; x < boardWidth; x++) {
      var px = margin + x * gridSize;
      var label = getColLabel(x);
      ctx.fillText(label, px, margin - offset);
      ctx.fillText(label, px, margin + (boardHeight - 1) * gridSize + offset);
    }

    for (var y = 0; y < boardHeight; y++) {
      var py = margin + y * gridSize;
      var num = getRowLabel(y, boardHeight);
      ctx.fillText(num, margin - offset, py);
      ctx.fillText(num, margin + (boardWidth - 1) * gridSize + offset, py);
    }
  }

  // ---------------------------------------------------------------------------
  // drawStones
  // ---------------------------------------------------------------------------
  function drawStones(ctx, state, boardWidth, boardHeight, margin, gridSize) {
    var stones = state.stones;
    if (!stones) return;

    var radius = gridSize * 0.47;

    for (var i = 0; i < stones.length; i++) {
      if (stones[i] === 0) continue;

      var x = Math.floor(i / boardHeight);
      var y = i % boardHeight;
      var px = margin + x * gridSize;
      var py = margin + y * gridSize;

      ctx.beginPath();
      ctx.arc(px, py, radius, 0, Math.PI * 2);
      ctx.fillStyle = stones[i] === 1 ? "#222" : "#eee";
      ctx.fill();
      ctx.strokeStyle = stones[i] === 1 ? "#000" : "#999";
      ctx.lineWidth = 1;
      ctx.stroke();
    }

    // Last move marker
    if (state.lastMove != null && Array.isArray(state.lastMove)) {
      var lx = state.lastMove[0];
      var ly = state.lastMove[1];
      var lpx = margin + lx * gridSize;
      var lpy = margin + ly * gridSize;
      var color = stones[lx * boardHeight + ly];
      ctx.beginPath();
      ctx.arc(lpx, lpy, radius * 0.35, 0, Math.PI * 2);
      ctx.strokeStyle = color === 1 ? "#eee" : "#222";
      ctx.lineWidth = 2;
      ctx.stroke();
    }
  }

  // ---------------------------------------------------------------------------
  // drawCandidates
  // ---------------------------------------------------------------------------
  function drawCandidates(ctx, bestMoves, boardHeight, margin, gridSize) {
    if (!bestMoves || bestMoves.length === 0) return;

    var radius = gridSize * 0.42;

    var maxPlayouts = 0;
    for (var i = 0; i < bestMoves.length; i++) {
      if (bestMoves[i].playouts > maxPlayouts) maxPlayouts = bestMoves[i].playouts;
    }

    for (var i = 0; i < bestMoves.length; i++) {
      var move = bestMoves[i];
      var xy = gtpToXY(move.coordinate, boardHeight);
      if (!xy) continue;

      var px = margin + xy[0] * gridSize;
      var py = margin + xy[1] * gridSize;

      var fillColor;
      if (i === 0) {
        fillColor = "rgba(0, 230, 230, 0.7)";
      } else {
        var fraction = maxPlayouts > 0 ? move.playouts / maxPlayouts : 0;
        fraction = Math.pow(fraction, 0.5);
        var r = Math.round((1 - fraction) * 217);
        var g = Math.round(fraction * 217);
        fillColor = "rgba(" + r + ", " + g + ", 0, 0.7)";
      }

      ctx.beginPath();
      ctx.arc(px, py, radius, 0, Math.PI * 2);
      ctx.fillStyle = fillColor;
      ctx.fill();

      // Text on candidate circle — adapt to enabled fields and available space
      ctx.fillStyle = "#fff";
      ctx.textAlign = "center";
      ctx.textBaseline = "middle";

      var lines = [];
      if (showCiWinrate && move.winrate != null) {
        lines.push({ text: move.winrate.toFixed(1), bold: true, weight: 1.0 });
      }
      if (showCiPlayouts) {
        lines.push({ text: formatPlayouts(move.playouts), bold: false, weight: 0.85 });
      }
      if (showCiScore && move.scoreMean != null) {
        var sign = move.scoreMean >= 0 ? "+" : "";
        lines.push({ text: sign + move.scoreMean.toFixed(1), bold: false, weight: 0.85 });
      }

      var n = lines.length;
      if (n === 0) continue;

      // Adapt to gridSize: very small only shows first line
      if (gridSize < 18 && n > 1) {
        lines = [lines[0]];
        n = 1;
      }

      var baseFont = n === 1 ? gridSize * 0.32
                   : n === 2 ? gridSize * 0.26
                              : gridSize * 0.22;
      var spacing = n === 1 ? 0
                  : n === 2 ? gridSize * 0.28
                            : gridSize * 0.25;
      var startY = py - spacing * (n - 1) / 2;

      for (var li = 0; li < n; li++) {
        var ln = lines[li];
        var fs = Math.max(6, baseFont * ln.weight);
        ctx.font = (ln.bold ? "bold " : "") + fs + "px sans-serif";
        ctx.fillText(ln.text, px, startY + li * spacing);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // drawHeatmap
  // ---------------------------------------------------------------------------
  function drawHeatmap(ctx, estimateArray, boardWidth, boardHeight, margin, gridSize) {
    if (!estimateArray) return;

    var halfGrid = gridSize * 0.48;

    for (var i = 0; i < estimateArray.length; i++) {
      var val = estimateArray[i];
      if (val === 0) continue;

      var x = Math.floor(i / boardHeight);
      var y = i % boardHeight;
      var px = margin + x * gridSize;
      var py = margin + y * gridSize;

      var alpha = Math.min(Math.abs(val), 1) * 0.5;
      // Positive = black territory (blue), negative = white territory (red)
      ctx.fillStyle =
        val > 0
          ? "rgba(33, 100, 243, " + alpha + ")"
          : "rgba(243, 67, 54, " + alpha + ")";
      ctx.fillRect(px - halfGrid, py - halfGrid, halfGrid * 2, halfGrid * 2);
    }
  }

  // ---------------------------------------------------------------------------
  // drawVariation
  // ---------------------------------------------------------------------------
  function drawVariation(ctx, variation, currentPlayer, boardHeight, margin, gridSize) {
    if (!variation || variation.length === 0) return;

    var radius = gridSize * 0.44;
    var player = currentPlayer === "W" ? 2 : 1; // start color
    var moveNum = 0;

    for (var i = 0; i < variation.length; i++) {
      moveNum++;
      var xy = gtpToXY(variation[i], boardHeight);
      if (!xy) {
        player = player === 1 ? 2 : 1;
        continue;
      }

      var px = margin + xy[0] * gridSize;
      var py = margin + xy[1] * gridSize;

      // Semi-transparent stone
      ctx.beginPath();
      ctx.arc(px, py, radius, 0, Math.PI * 2);
      ctx.fillStyle = player === 1 ? "rgba(34,34,34,0.6)" : "rgba(238,238,238,0.6)";
      ctx.fill();

      // Move number
      ctx.fillStyle = player === 1 ? "#eee" : "#222";
      ctx.font = "bold " + Math.max(10, gridSize * 0.36) + "px sans-serif";
      ctx.textAlign = "center";
      ctx.textBaseline = "middle";
      ctx.fillText(String(moveNum), px, py);

      // Alternate colour
      player = player === 1 ? 2 : 1;
    }
  }

  // ---------------------------------------------------------------------------
  // updateStatusBar
  // ---------------------------------------------------------------------------
  function updateStatusBar() {
    if (!boardState) return;

    var moveNum = document.getElementById("move-number");
    var curPlayer = document.getElementById("current-player");
    var wrEl = document.getElementById("winrate");
    var scoreEl = document.getElementById("score-mean");
    var playoutsEl = document.getElementById("playouts");

    moveNum.textContent = "手数: " + (boardState.moveNumber != null ? boardState.moveNumber : "-");
    curPlayer.textContent = "轮到: " + (boardState.currentPlayer === "B" ? "黑" : boardState.currentPlayer === "W" ? "白" : "-");

    if (boardState.winrate != null) {
      var displayWr = viewAsBlack ? boardState.winrate : 100 - boardState.winrate;
      wrEl.textContent = "胜率: " + displayWr.toFixed(1) + "%";
    } else {
      wrEl.textContent = "胜率: -";
    }

    if (boardState.scoreMean != null) {
      var sm = viewAsBlack ? boardState.scoreMean : -boardState.scoreMean;
      var absScore = Math.abs(sm).toFixed(1);
      var leader = sm > 0 ? (viewAsBlack ? "黑+" : "白+") : sm < 0 ? (viewAsBlack ? "白+" : "黑+") : "";
      scoreEl.textContent = "目差: " + leader + absScore;
    } else {
      scoreEl.textContent = "目差: -";
    }

    playoutsEl.textContent = "计算: " + formatPlayouts(boardState.playouts);

    // Show heatmap button only when estimate data is available
    var heatmapBtn = document.getElementById("toggle-heatmap");
    heatmapBtn.style.display = boardState.estimateArray ? "" : "none";
  }

  // ---------------------------------------------------------------------------
  // renderWinrateChart
  // ---------------------------------------------------------------------------
  function renderWinrateChart() {
    var container = document.getElementById("info-panel");
    var w = chartCanvas.clientWidth || container.clientWidth;
    var h = chartCanvas.clientHeight || 150;
    if (w <= 0 || h <= 0) return;

    var dpr = window.devicePixelRatio || 1;
    chartCanvas.width = w * dpr;
    chartCanvas.height = h * dpr;
    chartCanvas.style.width = w + "px";
    chartCanvas.style.height = h + "px";

    var ctx = chartCtx;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.fillStyle = "rgba(24, 28, 32, 0.92)";
    ctx.fillRect(0, 0, w, h);

    var padLeft = 30, padRight = 8, padTop = 6, padBottom = 18;
    var chartW = w - padLeft - padRight;
    var chartH = h - padTop - padBottom;

    var gridValues = [0, 25, 50, 75, 100];
    ctx.strokeStyle = "rgba(255, 255, 255, 0.08)";
    ctx.lineWidth = 1;
    ctx.setLineDash([3, 3]);
    ctx.fillStyle = "#888";
    ctx.font = "10px sans-serif";
    ctx.textAlign = "right";
    ctx.textBaseline = "middle";
    for (var gi = 0; gi < gridValues.length; gi++) {
      var gv = gridValues[gi];
      var gy = padTop + chartH - (gv / 100) * chartH;
      ctx.beginPath();
      ctx.moveTo(padLeft, gy);
      ctx.lineTo(padLeft + chartW, gy);
      ctx.stroke();
      ctx.fillText(gv + "%", padLeft - 3, gy);
    }
    ctx.setLineDash([]);

    ctx.strokeStyle = "rgba(255, 255, 255, 0.2)";
    ctx.setLineDash([4, 4]);
    ctx.beginPath();
    ctx.moveTo(padLeft, padTop + chartH / 2);
    ctx.lineTo(padLeft + chartW, padTop + chartH / 2);
    ctx.stroke();
    ctx.setLineDash([]);

    if (!winrateHistory || winrateHistory.length === 0) return;

    var len = winrateHistory.length;
    drawXAxisLabels(ctx, len, padLeft, chartW, padTop + chartH);

    ctx.strokeStyle = "rgba(100, 180, 255, 0.9)";
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    var started = false;
    for (var i = 0; i < len; i++) {
      var entry = winrateHistory[i];
      if (entry.winrate == null) { started = false; continue; }
      var px = padLeft + (i / Math.max(len - 1, 1)) * chartW;
      var wv = entry.winrate;
      if (!viewAsBlack) wv = 100 - wv;
      var py = padTop + chartH - (wv / 100) * chartH;
      if (!started) { ctx.moveTo(px, py); started = true; }
      else ctx.lineTo(px, py);
    }
    ctx.stroke();
    drawChartHoverLine(ctx, len, padLeft, chartW, padTop, chartH);
  }

  function renderScoreChart() {
    var container = document.getElementById("info-panel");
    var w = scoreCanvas.clientWidth || container.clientWidth;
    var h = scoreCanvas.clientHeight || 150;
    if (w <= 0 || h <= 0) return;

    var dpr = window.devicePixelRatio || 1;
    scoreCanvas.width = w * dpr;
    scoreCanvas.height = h * dpr;
    scoreCanvas.style.width = w + "px";
    scoreCanvas.style.height = h + "px";

    var ctx = scoreCtx;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.fillStyle = "rgba(24, 28, 32, 0.92)";
    ctx.fillRect(0, 0, w, h);

    var padLeft = 30, padRight = 8, padTop = 6, padBottom = 18;
    var chartW = w - padLeft - padRight;
    var chartH = h - padTop - padBottom;

    var gridValues = [-40, -20, 0, 20, 40];
    ctx.strokeStyle = "rgba(255, 255, 255, 0.08)";
    ctx.lineWidth = 1;
    ctx.setLineDash([3, 3]);
    ctx.fillStyle = "#888";
    ctx.font = "10px sans-serif";
    ctx.textAlign = "right";
    ctx.textBaseline = "middle";
    for (var gi = 0; gi < gridValues.length; gi++) {
      var gv = gridValues[gi];
      var gy = padTop + chartH / 2 - (gv / 50) * (chartH / 2);
      ctx.beginPath();
      ctx.moveTo(padLeft, gy);
      ctx.lineTo(padLeft + chartW, gy);
      ctx.stroke();
      ctx.fillText(gv > 0 ? "+" + gv : String(gv), padLeft - 3, gy);
    }
    ctx.setLineDash([]);

    ctx.strokeStyle = "rgba(255, 255, 255, 0.2)";
    ctx.setLineDash([4, 4]);
    ctx.beginPath();
    ctx.moveTo(padLeft, padTop + chartH / 2);
    ctx.lineTo(padLeft + chartW, padTop + chartH / 2);
    ctx.stroke();
    ctx.setLineDash([]);

    if (!winrateHistory || winrateHistory.length === 0) return;

    var len = winrateHistory.length;
    drawXAxisLabels(ctx, len, padLeft, chartW, padTop + chartH);

    ctx.strokeStyle = "rgba(255, 200, 50, 0.9)";
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    var startedSc = false;
    for (var i = 0; i < len; i++) {
      var entry = winrateHistory[i];
      if (entry.scoreMean == null) { startedSc = false; continue; }
      var px = padLeft + (i / Math.max(len - 1, 1)) * chartW;
      var sv = entry.scoreMean;
      if (!viewAsBlack) sv = -sv;
      sv = Math.max(-50, Math.min(50, sv));
      var py = padTop + chartH / 2 - (sv / 50) * (chartH / 2);
      if (!startedSc) { ctx.moveTo(px, py); startedSc = true; }
      else ctx.lineTo(px, py);
    }
    ctx.stroke();
    drawChartHoverLine(ctx, len, padLeft, chartW, padTop, chartH);
  }

  function drawChartHoverLine(ctx, len, padLeft, chartW, padTop, chartH) {
    if (chartHoverIdx < 0 || chartHoverIdx >= len) return;
    var hx = padLeft + (chartHoverIdx / Math.max(len - 1, 1)) * chartW;
    ctx.strokeStyle = "rgba(120, 220, 255, 0.6)";
    ctx.lineWidth = 1;
    ctx.setLineDash([3, 3]);
    ctx.beginPath();
    ctx.moveTo(hx, padTop);
    ctx.lineTo(hx, padTop + chartH);
    ctx.stroke();
    ctx.setLineDash([]);
  }

  function drawXAxisLabels(ctx, len, padLeft, chartW, bottom) {
    ctx.fillStyle = "#888";
    ctx.font = "10px sans-serif";
    ctx.textAlign = "center";
    ctx.textBaseline = "top";
    var step = len <= 50 ? 10 : len <= 150 ? 20 : 50;
    for (var mi = step; mi < len; mi += step) {
      var mx = padLeft + (mi / Math.max(len - 1, 1)) * chartW;
      ctx.fillText(String(mi), mx, bottom + 3);
    }
  }

  function getBlunderColor(drop) {
    if (drop >= 20) return "#eb3c3c";
    if (drop >= 10) return "#e68c28";
    if (drop >= 5) return "#e6c832";
    if (drop >= 2) return "#96b450";
    return "#787878";
  }

  function renderBlunderList() {
    var container = document.getElementById("blunder-list");
    if (!winrateHistory || winrateHistory.length < 2) {
      container.innerHTML = '<div class="blunder-empty">当前无问题手</div>';
      return;
    }

    var blunders = [];
    for (var i = 1; i < winrateHistory.length; i++) {
      var prev = winrateHistory[i - 1];
      var curr = winrateHistory[i];
      if (prev.winrate == null || curr.winrate == null) continue;
      // Mover of curr is the opposite of curr.blackToPlay (blackToPlay = next).
      // Convert drop to mover's perspective so White blunders register too.
      // Fallback to i parity for older server without blackToPlay field.
      var moverIsBlack = curr.blackToPlay != null
        ? (curr.blackToPlay === false)
        : (i % 2 === 1);
      var wrDropMover = moverIsBlack
        ? prev.winrate - curr.winrate
        : curr.winrate - prev.winrate;
      if (wrDropMover >= 5) {
        var scoreDropMover = null;
        if (prev.scoreMean != null && curr.scoreMean != null) {
          var rawDrop = prev.scoreMean - curr.scoreMean;
          scoreDropMover = moverIsBlack ? rawDrop : -rawDrop;
        }
        blunders.push({
          moveNumber: curr.moveNumber,
          drop: wrDropMover,
          scoreDrop: scoreDropMover,
          isBlack: moverIsBlack
        });
      }
    }

    blunders.sort(function (a, b) { return b.drop - a.drop; });

    if (blunders.length === 0) {
      container.innerHTML = '<div class="blunder-empty">当前无问题手</div>';
      return;
    }

    var html = "";
    for (var j = 0; j < blunders.length; j++) {
      var b = blunders[j];
      var color = getBlunderColor(b.drop);
      var side = b.isBlack ? "黑" : "白";
      var scoreText = b.scoreDrop != null ? " 目差-" + Math.abs(b.scoreDrop).toFixed(1) : "";
      html += '<div class="blunder-item">'
        + '<span class="blunder-dot" style="background:' + color + '"></span>'
        + '<span>' + side + " 第" + b.moveNumber + "手 胜率-" + b.drop.toFixed(1) + "%" + scoreText + '</span>'
        + '</div>';
    }
    container.innerHTML = html;
  }

  function handleChartHover(e, canvas, type) {
    if (!winrateHistory || winrateHistory.length === 0) {
      chartTooltip.style.display = "none";
      if (chartHoverIdx >= 0) { chartHoverIdx = -1; renderWinrateChart(); renderScoreChart(); }
      return;
    }
    var rect = canvas.getBoundingClientRect();
    var mx = e.clientX - rect.left;
    var w = parseInt(canvas.style.width) || canvas.width;
    var padLeft = 30, padRight = 8;
    var chartW = w - padLeft - padRight;
    var len = winrateHistory.length;
    var idx = Math.round(((mx - padLeft) / chartW) * (len - 1));
    if (idx < 0 || idx >= len) {
      chartTooltip.style.display = "none";
      if (chartHoverIdx >= 0) { chartHoverIdx = -1; renderWinrateChart(); renderScoreChart(); }
      return;
    }
    if (idx !== chartHoverIdx) {
      chartHoverIdx = idx;
      renderWinrateChart();
      renderScoreChart();
    }
    var entry = winrateHistory[idx];
    var text;
    if (type === "winrate") {
      if (entry.winrate == null) {
        text = "第" + entry.moveNumber + "手  (未分析)";
      } else {
        var wv = entry.winrate;
        if (!viewAsBlack) wv = 100 - wv;
        text = "第" + entry.moveNumber + "手  胜率: " + wv.toFixed(1) + "%";
      }
    } else {
      if (entry.scoreMean == null) {
        text = "第" + entry.moveNumber + "手  (未分析)";
      } else {
        var sv = entry.scoreMean;
        if (!viewAsBlack) sv = -sv;
        var abs = Math.abs(sv).toFixed(1);
        var ldr = sv > 0 ? (viewAsBlack ? "黑+" : "白+") : sv < 0 ? (viewAsBlack ? "白+" : "黑+") : "";
        text = "第" + entry.moveNumber + "手  目差: " + ldr + abs;
      }
    }
    chartTooltip.textContent = text;
    chartTooltip.style.display = "block";
    chartTooltip.style.left = "0px";
    chartTooltip.style.top = "0px";
    var tipW = chartTooltip.offsetWidth;
    var tipH = chartTooltip.offsetHeight;
    var vw = window.innerWidth;
    var left = e.clientX + 12;
    if (left + tipW + 4 > vw) left = e.clientX - tipW - 12;
    if (left < 4) left = 4;
    var top = e.clientY - tipH - 8;
    if (top < 4) top = e.clientY + 16;
    chartTooltip.style.left = left + "px";
    chartTooltip.style.top = top + "px";
  }

  chartCanvas.addEventListener("mousemove", function (e) { handleChartHover(e, chartCanvas, "winrate"); });
  chartCanvas.addEventListener("mouseleave", function () { chartTooltip.style.display = "none"; chartHoverIdx = -1; renderWinrateChart(); renderScoreChart(); });
  scoreCanvas.addEventListener("mousemove", function (e) { handleChartHover(e, scoreCanvas, "score"); });
  scoreCanvas.addEventListener("mouseleave", function () { chartTooltip.style.display = "none"; chartHoverIdx = -1; renderWinrateChart(); renderScoreChart(); });

  // ---------------------------------------------------------------------------
  // Trial mode helpers
  // ---------------------------------------------------------------------------
  function sendTrial(type, extra) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    var msg = { type: type, clientId: clientId };
    if (extra) {
      for (var k in extra) {
        if (Object.prototype.hasOwnProperty.call(extra, k)) msg[k] = extra[k];
      }
    }
    ws.send(JSON.stringify(msg));
  }

  function applyTrialUiState() {
    var enterBtn = document.getElementById("trial-enter-btn");
    var activeControls = document.getElementById("trial-active-controls");
    var followText = document.getElementById("trial-follow-text");
    var statusText = document.getElementById("trial-status-text");
    var backBtn = document.getElementById("trial-back-btn");
    var forwardBtn = document.getElementById("trial-forward-btn");

    if (!trialState) {
      enterBtn.style.display = "";
      activeControls.style.display = "none";
      followText.style.display = "none";
      return;
    }
    if (trialState.ownerClientId === clientId) {
      enterBtn.style.display = "none";
      activeControls.style.display = "";
      followText.style.display = "none";
      statusText.textContent =
        "试下中：从第 " + trialState.anchorMoveNumber + " 手起，已下到第 "
        + trialState.displayMoveNumber + " 手";
      backBtn.disabled = !trialState.canBack;
      forwardBtn.disabled = !trialState.canForward;
    } else {
      enterBtn.style.display = "none";
      activeControls.style.display = "none";
      followText.style.display = "";
      followText.textContent = "他人正在试下中（从第 " + trialState.anchorMoveNumber + " 手起）";
    }
  }

  function pixelToBoardCoord(e) {
    if (!boardState) return null;
    var rect = boardCanvas.getBoundingClientRect();
    var pxScreen = e.clientX - rect.left;
    var pyScreen = e.clientY - rect.top;

    // render() 用 container.clientWidth/Height 算 size 并把棋子按 size 画到内部画面。
    // 但浏览器实际显示的 canvas（rect）可能因 CSS / layout 被压缩成非正方形，
    // 屏幕坐标 (pxScreen, pyScreen) 在 rect.width × rect.height 上，需缩回内部 size × size 坐标系。
    var container = document.getElementById("board-container");
    var size = Math.min(container.clientWidth, container.clientHeight);
    if (size <= 0 || rect.width <= 0 || rect.height <= 0) return null;
    var px = pxScreen * size / rect.width;
    var py = pyScreen * size / rect.height;
    var boardWidth = boardState.boardWidth || 19;
    var boardHeight = boardState.boardHeight || 19;
    var margin = coordStyle !== "off" ? size * 0.06 : size * 0.04;
    var gridSize = (size - 2 * margin) / (Math.max(boardWidth, boardHeight) - 1);

    var cx = Math.round((px - margin) / gridSize);
    var cy = Math.round((py - margin) / gridSize);
    if (cx < 0 || cx >= boardWidth || cy < 0 || cy >= boardHeight) return null;
    return { x: cx, y: cy };
  }

  function drawSiblingMarkers(ctx, markers, boardHeight, margin, gridSize) {
    ctx.save();
    for (var i = 0; i < markers.length; i++) {
      var m = markers[i];
      var px = margin + m.x * gridSize;
      var py = margin + m.y * gridSize;
      ctx.beginPath();
      ctx.arc(px, py, gridSize * 0.35, 0, Math.PI * 2);
      ctx.fillStyle = "rgba(255, 200, 0, 0.85)";
      ctx.fill();
      ctx.lineWidth = 1.5;
      ctx.strokeStyle = "rgba(120, 80, 0, 1)";
      ctx.stroke();
      ctx.fillStyle = "black";
      ctx.font = "bold " + Math.floor(gridSize * 0.45) + "px sans-serif";
      ctx.textAlign = "center";
      ctx.textBaseline = "middle";
      ctx.fillText(m.label, px, py);
    }
    ctx.restore();
  }

  // ---------------------------------------------------------------------------
  // Mouse interaction
  // ---------------------------------------------------------------------------
  boardCanvas.addEventListener("mousemove", function (e) {
    if (!boardState || !boardState.bestMoves) {
      hoveredMove = null;
      return;
    }

    var rect = boardCanvas.getBoundingClientRect();
    var mxScreen = e.clientX - rect.left;
    var myScreen = e.clientY - rect.top;

    var boardWidth = boardState.boardWidth || 19;
    var boardHeight = boardState.boardHeight || 19;
    // 跟 pixelToBoardCoord 一致：屏幕坐标按 rect 比例缩回内部 size 坐标系
    var container = document.getElementById("board-container");
    var size = Math.min(container.clientWidth, container.clientHeight);
    if (size <= 0 || rect.width <= 0 || rect.height <= 0) return;
    var mx = mxScreen * size / rect.width;
    var my = myScreen * size / rect.height;
    var margin = coordStyle !== "off" ? size * 0.06 : size * 0.04;
    var gridSize = (size - 2 * margin) / (Math.max(boardWidth, boardHeight) - 1);

    var found = null;
    var bestDist = gridSize * 0.5;

    for (var i = 0; i < boardState.bestMoves.length; i++) {
      var move = boardState.bestMoves[i];
      var xy = gtpToXY(move.coordinate, boardHeight);
      if (!xy) continue;

      var px = margin + xy[0] * gridSize;
      var py = margin + xy[1] * gridSize;
      var dist = Math.sqrt((mx - px) * (mx - px) + (my - py) * (my - py));
      if (dist < bestDist) {
        bestDist = dist;
        found = move;
      }
    }

    var foundCoord = found ? found.coordinate : null;
    var hoveredCoord = hoveredMove ? hoveredMove.coordinate : null;
    if (foundCoord !== hoveredCoord) {
      hoveredMove = found;
      render();
    }
  });

  boardCanvas.addEventListener("mouseleave", function () {
    if (hoveredMove) {
      hoveredMove = null;
      render();
    }
  });

  boardCanvas.addEventListener("click", function (e) {
    if (!trialState || trialState.ownerClientId !== clientId) return;
    var coord = pixelToBoardCoord(e);
    if (!coord) return;
    if (siblingMarkers) {
      for (var i = 0; i < siblingMarkers.length; i++) {
        var m = siblingMarkers[i];
        if (m.x === coord.x && m.y === coord.y) {
          sendTrial("trial_navigate", { direction: "forward", childIndex: m.childIndex });
          return;
        }
      }
    }
    // 落子瞬间清掉 hoveredMove + 重画，避免分支预览/选点高亮残留
    // 直到鼠标挪开才消失（鼠标位置没变 → mousemove 不会触发，hoveredMove 还指向旧 move）
    if (hoveredMove) {
      hoveredMove = null;
      render();
    }
    sendTrial("trial_move", { x: coord.x, y: coord.y });
  });

  // Mobile touch support
  boardCanvas.addEventListener("touchstart", function (e) {
    if (!boardState || !boardState.bestMoves) return;

    var touch = e.touches[0];
    var rect = boardCanvas.getBoundingClientRect();
    var mxScreen = touch.clientX - rect.left;
    var myScreen = touch.clientY - rect.top;
    var snap = boardState;

    longPressTimer = setTimeout(function () {
      if (!snap || !snap.bestMoves) return;
      var boardHeight = snap.boardHeight || 19;
      var boardWidth = snap.boardWidth || 19;
      var container = document.getElementById("board-container");
      var size = Math.min(container.clientWidth, container.clientHeight);
      if (size <= 0 || rect.width <= 0 || rect.height <= 0) return;
      var mx = mxScreen * size / rect.width;
      var my = myScreen * size / rect.height;
      var margin = coordStyle !== "off" ? size * 0.06 : size * 0.04;
      var gridSize = (size - 2 * margin) / (Math.max(boardWidth, boardHeight) - 1);

      var found = null;
      var bestDist = gridSize * 0.5;

      for (var i = 0; i < snap.bestMoves.length; i++) {
        var move = snap.bestMoves[i];
        var xy = gtpToXY(move.coordinate, boardHeight);
        if (!xy) continue;

        var px = margin + xy[0] * gridSize;
        var py = margin + xy[1] * gridSize;
        var dist = Math.sqrt((mx - px) * (mx - px) + (my - py) * (my - py));
        if (dist < bestDist) {
          bestDist = dist;
          found = move;
        }
      }

      if (found !== hoveredMove) {
        hoveredMove = found;
        render();
      }
    }, 500);
  }, { passive: true });

  boardCanvas.addEventListener("touchend", function () {
    clearTimeout(longPressTimer);
    if (hoveredMove) {
      hoveredMove = null;
      render();
    }
  });

  // ---------------------------------------------------------------------------
  // Control buttons
  // ---------------------------------------------------------------------------
  document.getElementById("toggle-heatmap").addEventListener("click", function () {
    heatmapEnabled = !heatmapEnabled;
    this.classList.toggle("active", heatmapEnabled);
    savePrefs();
    render();
  });

  document.getElementById("toggle-perspective").addEventListener("click", function () {
    viewAsBlack = !viewAsBlack;
    var side = viewAsBlack ? "白" : "黑";
    this.textContent = "切换" + side + "方视角";
    var label = viewAsBlack ? "黑方视角" : "白方视角";
    document.getElementById("chart-label-winrate").textContent = "胜率曲线（" + label + "）";
    document.getElementById("chart-label-score").textContent = "目差曲线（" + label + "）";
    savePrefs();
    render();
    renderWinrateChart();
    renderScoreChart();
  });

  document.getElementById("coord-style").addEventListener("change", function () {
    coordStyle = this.value;
    savePrefs();
    render();
  });

  document.getElementById("ci-winrate").addEventListener("change", function () {
    showCiWinrate = this.checked;
    savePrefs();
    render();
  });
  document.getElementById("ci-playouts").addEventListener("change", function () {
    showCiPlayouts = this.checked;
    savePrefs();
    render();
  });
  document.getElementById("ci-score").addEventListener("change", function () {
    showCiScore = this.checked;
    savePrefs();
    render();
  });

  // 把持久化偏好同步到 UI 控件，并触发一次依赖渲染
  (function applyPrefsToUi() {
    var heatBtn = document.getElementById("toggle-heatmap");
    if (heatBtn) heatBtn.classList.toggle("active", heatmapEnabled);
    var persBtn = document.getElementById("toggle-perspective");
    if (persBtn) {
      persBtn.textContent = "切换" + (viewAsBlack ? "白" : "黑") + "方视角";
    }
    var winrateLabel = document.getElementById("chart-label-winrate");
    if (winrateLabel) {
      winrateLabel.textContent = "胜率曲线（" + (viewAsBlack ? "黑方视角" : "白方视角") + "）";
    }
    var scoreLabel = document.getElementById("chart-label-score");
    if (scoreLabel) {
      scoreLabel.textContent = "目差曲线（" + (viewAsBlack ? "黑方视角" : "白方视角") + "）";
    }
    var coordSel = document.getElementById("coord-style");
    if (coordSel) coordSel.value = coordStyle;
    var ciW = document.getElementById("ci-winrate");
    if (ciW) ciW.checked = showCiWinrate;
    var ciP = document.getElementById("ci-playouts");
    if (ciP) ciP.checked = showCiPlayouts;
    var ciS = document.getElementById("ci-score");
    if (ciS) ciS.checked = showCiScore;
  })();

  document.getElementById("trial-enter-btn").addEventListener("click", function () {
    sendTrial("enter_trial");
  });
  document.getElementById("trial-exit-btn").addEventListener("click", function () {
    sendTrial("exit_trial");
  });
  document.getElementById("trial-back-btn").addEventListener("click", function () {
    sendTrial("trial_navigate", { direction: "back" });
  });
  document.getElementById("trial-forward-btn").addEventListener("click", function () {
    sendTrial("trial_navigate", { direction: "forward" });
  });
  document.getElementById("trial-reset-btn").addEventListener("click", function () {
    sendTrial("trial_reset");
  });

  // ---------------------------------------------------------------------------
  // Resize
  // ---------------------------------------------------------------------------
  window.addEventListener("resize", function () {
    render();
    renderWinrateChart();
    renderScoreChart();
  });

  // ---------------------------------------------------------------------------
  // Init
  // ---------------------------------------------------------------------------
  applyTrialUiState();
  connectWs();
})();
