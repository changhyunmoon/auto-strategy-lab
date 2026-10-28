/* 지표 데이터셋 상세: 열 목록 · 통계 · 행 보기 */
$(function () {
  var F = window.FEATURE;
  var KIND_LABEL = { VOLUME: '거래량', MA: '이동평균선', RSI: 'RSI', MACD: 'MACD', ATR: 'ATR', BB: '볼린저 밴드' };
  Glossary.addColumns(F.columns);
  $('#configJson').text(JSON.stringify(F.config, null, 2));

  var $info = $('#jobInfo');
  if ($info.length) App.watchJob($('#jobBox'), $info.data('job'), function () { window.location.reload(); });

  // ------------------------------------------------------------ 지표별 수
  var $cards = $('#kindCards');
  Object.keys(KIND_LABEL).forEach(function (k) {
    var n = F.columns.filter(function (c) { return c.kind === k; }).length;
    $cards.append($('<div class="card-stat"></div>').append($('<div class="label"></div>').text(KIND_LABEL[k]))
      .append($('<div class="value"></div>').text(n)).append($('<div class="sub"></div>').text(n ? '열' : '만들지 않음')));
  });

  // ------------------------------------------------------------ 열 목록
  var head = '<tr><th>#</th><th>열 이름</th><th>설명</th><th>지표</th><th>설정</th><th class="num">워밍업</th>';
  F.marketIds.forEach(function (m) {
    var code = (F.marketLabels[m] || '').split(' ')[0];
    head += '<th class="num">' + App.esc(code) + ' null</th><th>' + App.esc(code) + ' 첫 유효 (KST)</th>';
  });
  $('#colHead').html(head + '</tr>');

  function renderCols() {
    var q = $.trim($('#colSearch').val()).toLowerCase(), kind = $('#colKind').val();
    var list = F.columns.filter(function (c) {
      return (!kind || c.kind === kind) && (!q || (c.name + ' ' + c.title + ' ' + c.formula).toLowerCase().indexOf(q) >= 0);
    });
    $('#colCount').text(list.length === F.columns.length ? list.length + '개' : list.length + ' / ' + F.columns.length + '개');
    var html = list.slice(0, 2000).map(function (c) {
      var row = '<tr><td class="num faint">' + (c.index + 1) + '</td>' +
        '<td class="mono"><span class="hover-name" data-hover-term="col_' + App.esc(c.name) + '">' + App.esc(c.name) + '</span></td>' +
        '<td>' + App.esc(c.title) + '</td><td class="small">' + App.esc(c.kindLabel) + '</td>' +
        '<td class="small muted">' + App.esc(c.params) + '</td><td class="num">' + c.warmup + '</td>';
      F.marketIds.forEach(function (m) {
        var s = (c.stats || []).filter(function (x) { return x.marketId === m; })[0];
        row += s ? '<td class="num">' + App.formatNumber(s.nulls) + '</td><td class="num small">' + (s.firstValid ? App.toKst(s.firstValid) : '<span class="faint">값 없음</span>') + '</td>'
          : '<td class="faint">–</td><td class="faint">–</td>';
      });
      return row + '</tr>';
    }).join('');
    $('#colBody').html(html);
  }
  $('#colSearch').on('input', App.debounce(renderCols, 150));
  $('#colKind').on('change', renderCols);
  renderCols();

  // ------------------------------------------------------------ 행 보기
  if (!F.ready) return;
  var offset = 0, total = 0, LIMIT = 50;

  function load(params) {
    var m = $('#rowMarket').val();
    $('#exportLink').attr('href', '/api/features/' + F.id + '/export.csv?marketId=' + m);
    App.api('GET', '/api/features/' + F.id + '/rows', $.extend({ marketId: m, limit: LIMIT, kind: $('#rowKind').val() }, params)).done(function (res) {
      offset = res.offset;
      total = res.total;
      $('#rowInfo').text(App.formatNumber(offset + 1) + ' ~ ' + App.formatNumber(Math.min(total, offset + res.rows.length)) + ' / ' + App.formatNumber(total) + '행');
      var base = ['open', 'high', 'low', 'close', 'volume'];
      var $h = $('<tr><th>시각 (KST)</th></tr>');
      base.forEach(function (b) { $h.append($('<th class="num"></th>').append($('<span></span>').attr('data-term', b).text(b))); });
      res.columns.forEach(function (c) { $h.append($('<th class="num mono"></th>').append($('<span class="hover-name"></span>').attr('data-hover-term', 'col_' + c).text(c))); });
      $('#previewTable thead').empty().append($h);
      var html = res.rows.map(function (r) {
        var s = '<tr><td class="num">' + App.toKst(r.time) + '</td>';
        base.forEach(function (b) { s += '<td class="num" style="background:var(--bg-sidebar)">' + App.smart(r[b]) + '</td>'; });
        res.columns.forEach(function (c) { s += '<td class="num">' + (r[c] == null ? '<span class="faint">null</span>' : App.smart(r[c])) + '</td>'; });
        return s + '</tr>';
      }).join('');
      $('#previewTable tbody').html(html);
    });
  }

  $('#rowMarket, #rowKind').on('change', function () { load({ offset: offset }); });
  $('#rowFirst').on('click', function () { load({ offset: 0 }); });
  $('#rowPrev').on('click', function () { load({ offset: Math.max(0, offset - LIMIT) }); });
  $('#rowNext').on('click', function () { load({ offset: Math.min(Math.max(0, total - LIMIT), offset + LIMIT) }); });
  $('#rowLast').on('click', function () { load({ offset: Math.max(0, total - LIMIT) }); });
  $('#rowGo').on('click', function () {
    var v = $('#rowAt').val();
    if (v) load({ at: new Date(v + ':00Z').toISOString() });
  });
  load({ offset: 0 });
});
