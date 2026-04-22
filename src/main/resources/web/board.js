(function () {
  "use strict";

  // ---------------------------------------------------------------------------
  // State
  // ---------------------------------------------------------------------------
  var boardState = null;
  var analysisData = null;
  var winrateHistory = null;
  var hoveredMove = null;
  var heatmapEnabled = false;
  var showScoreCurve = false;
  var ws = null;
  var reconnectDelay = 1000;
  var longPressTimer = null;

  // DOM references
  var boardCanvas = document.getElementById("board-canvas");
  var boardCtx = boardCanvas.getContext("2d");
  var chartCanvas = document.getElementById("winrate-chart");
  var chartCtx = chartCanvas.getContext("2d");
  var overlay = document.getElementById("connection-overlay");

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
    var margin = size * 0.04;
    var gridSize = (size - 2 * margin) / (Math.max(boardWidth, boardHeight) - 1);

    drawBoard(boardCtx, size, boardWidth, boardHeight, margin, gridSize);
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

    // Star points (hoshi) for 19x19
    if (boardWidth === 19 && boardHeight === 19) {
      var stars = [
        [3, 3], [3, 9], [3, 15],
        [9, 3], [9, 9], [9, 15],
        [15, 3], [15, 9], [15, 15]
      ];
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

    for (var i = 0; i < bestMoves.length; i++) {
      var move = bestMoves[i];
      var xy = gtpToXY(move.coordinate, boardHeight);
      if (!xy) continue;

      var px = margin + xy[0] * gridSize;
      var py = margin + xy[1] * gridSize;

      // Color by rank
      var fillColor;
      if (i === 0) fillColor = "rgba(76, 175, 80, 0.7)";       // green – best
      else if (i === 1) fillColor = "rgba(33, 150, 243, 0.7)";  // blue
      else if (i === 2) fillColor = "rgba(255, 152, 0, 0.7)";   // orange
      else fillColor = "rgba(158, 158, 158, 0.6)";               // gray

      ctx.beginPath();
      ctx.arc(px, py, radius, 0, Math.PI * 2);
      ctx.fillStyle = fillColor;
      ctx.fill();

      // Winrate text
      ctx.fillStyle = "#fff";
      ctx.font = "bold " + Math.max(10, gridSize * 0.32) + "px sans-serif";
      ctx.textAlign = "center";
      ctx.textBaseline = "middle";

      var wr = move.winrate != null ? move.winrate.toFixed(1) + "%" : "";
      ctx.fillText(wr, px, py - gridSize * 0.12);

      // Score mean text
      if (move.scoreMean != null) {
        ctx.font = Math.max(8, gridSize * 0.24) + "px sans-serif";
        var sign = move.scoreMean >= 0 ? "+" : "";
        ctx.fillText(sign + move.scoreMean.toFixed(1), px, py + gridSize * 0.18);
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

    for (var i = 0; i < variation.length; i++) {
      var xy = gtpToXY(variation[i], boardHeight);
      if (!xy) continue;

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
      ctx.fillText(String(i + 1), px, py);

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
      wrEl.textContent = "胜率: " + boardState.winrate.toFixed(1) + "%";
    } else {
      wrEl.textContent = "胜率: -";
    }

    if (boardState.scoreMean != null) {
      var sign = boardState.scoreMean >= 0 ? "+" : "";
      scoreEl.textContent = "目差: " + sign + boardState.scoreMean.toFixed(1);
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
    var h = chartCanvas.clientHeight || 120;
    if (w <= 0 || h <= 0) return;

    chartCanvas.width = w;
    chartCanvas.height = h;

    var ctx = chartCtx;
    // Background
    ctx.fillStyle = "#111";
    ctx.fillRect(0, 0, w, h);

    if (!winrateHistory || winrateHistory.length === 0) return;

    // 50% dashed baseline
    ctx.setLineDash([4, 4]);
    ctx.strokeStyle = "#555";
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(0, h / 2);
    ctx.lineTo(w, h / 2);
    ctx.stroke();
    ctx.setLineDash([]);

    // Plot
    var len = winrateHistory.length;
    ctx.strokeStyle = "#4CAF50";
    ctx.lineWidth = 1.5;
    ctx.beginPath();

    for (var i = 0; i < len; i++) {
      var entry = winrateHistory[i];
      var val;
      if (showScoreCurve && entry.scoreMean != null) {
        // Map scoreMean to 0-100 range: scoreMean + 50, clamped
        val = Math.max(0, Math.min(100, entry.scoreMean + 50));
      } else {
        val = entry.winrate != null ? entry.winrate : 50;
      }

      var x = (i / Math.max(len - 1, 1)) * w;
      var y = h - (val / 100) * h;

      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    }
    ctx.stroke();
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
    var mx = e.clientX - rect.left;
    var my = e.clientY - rect.top;

    var boardWidth = boardState.boardWidth || 19;
    var boardHeight = boardState.boardHeight || 19;
    var size = parseInt(boardCanvas.style.width) || boardCanvas.width;
    var margin = size * 0.04;
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

    if (found !== hoveredMove) {
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

  // Mobile touch support
  boardCanvas.addEventListener("touchstart", function (e) {
    if (!boardState || !boardState.bestMoves) return;

    var touch = e.touches[0];
    var rect = boardCanvas.getBoundingClientRect();
    var mx = touch.clientX - rect.left;
    var my = touch.clientY - rect.top;

    longPressTimer = setTimeout(function () {
      var boardHeight = boardState.boardHeight || 19;
      var boardWidth = boardState.boardWidth || 19;
      var size = boardCanvas.width;
      var margin = size * 0.04;
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
    render();
  });

  document.getElementById("toggle-score").addEventListener("click", function () {
    showScoreCurve = !showScoreCurve;
    this.classList.toggle("active", showScoreCurve);
    renderWinrateChart();
  });

  // ---------------------------------------------------------------------------
  // Resize
  // ---------------------------------------------------------------------------
  window.addEventListener("resize", function () {
    render();
    renderWinrateChart();
  });

  // ---------------------------------------------------------------------------
  // Init
  // ---------------------------------------------------------------------------
  connectWs();
})();
