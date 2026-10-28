/* 기술 지표 만들기: 설정 입력 ↔ 미리보기(/api/features/plan) ↔ 생성 */
$(function () {
  if (!$('#featureForm').length) return;
  var INIT = window.FEATURE_INIT;
  var KIND_LABEL = { VOLUME: '거래량', MA: '이동평균선', RSI: 'RSI', MACD: 'MACD', ATR: 'ATR', BB: '볼린저 밴드' };
  var bands = [];
  var lastPlan = null;
  var running = false;

  // ------------------------------------------------------------ 값 읽기 · 쓰기
  function getPath(obj, path) {
    return path.split('.').reduce(function (o, k) { return o == null ? undefined : o[k]; }, obj);
  }

  function setPath(obj, path, value) {
    var keys = path.split('.'), o = obj;
    for (var i = 0; i < keys.length - 1; i++) { o[keys[i]] = o[keys[i]] || {}; o = o[keys[i]]; }
    o[keys[keys.length - 1]] = value;
  }

  function apply(cfg) {
    $('[data-path]').each(function () {
      var $el = $(this), v = getPath(cfg, $el.data('path'));
      if ($el.is('.numset')) NumSet.set($el, v || []);
      else if ($el.is(':radio')) $el.prop('checked', $el.val() === v);
      else if ($el.is(':checkbox')) $el.prop('checked', !!v);
    });
    $('[data-list]').each(function () {
      var list = getPath(cfg, $(this).data('list')) || [];
      $(this).prop('checked', list.indexOf(this.value) >= 0);
    });
    bands = ((cfg.rsi && cfg.rsi.bands) || []).map(function (b) { return { lower: b.lower, upper: b.upper }; });
    renderBands();
    refreshCards();
  }

  function read() {
    var cfg = {};
    $('[data-path]').each(function () {
      var $el = $(this), path = $el.data('path');
      if ($el.is('.numset')) setPath(cfg, path, NumSet.get($el));
      else if ($el.is(':radio')) { if ($el.prop('checked')) setPath(cfg, path, $el.val()); }
      else if ($el.is(':checkbox')) setPath(cfg, path, $el.prop('checked'));
    });
    var lists = {};
    $('[data-list]').each(function () {
      var p = $(this).data('list');
      lists[p] = lists[p] || [];
      if (this.checked) lists[p].push(this.value);
    });
    Object.keys(lists).forEach(function (p) { setPath(cfg, p, lists[p]); });
    setPath(cfg, 'rsi.bands', bands.slice());
    return cfg;
  }

  // ------------------------------------------------------------ RSI 기준값 쌍
  function renderBands() {
    var $c = $('#bandChips').empty();
    bands.forEach(function (b, i) {
      $('<span class="numset-chip"></span>').text('(' + b.lower + ', ' + b.upper + ')')
        .append($('<button type="button" class="numset-x band-x" aria-label="지우기">×</button>').data('i', i)).appendTo($c);
    });
    if (!bands.length) $c.append('<span class="faint small">없음</span>');
  }
  $('#bandAdd').on('click', function () {
    var lo = Number($('#bandLower').val()), up = Number($('#bandUpper').val());
    if ($('#bandLower').val() === '' || $('#bandUpper').val() === '' || isNaN(lo) || isNaN(up) || !(lo >= 0 && lo < up && up <= 100)) {
      App.toast('하단 · 상단은 0 ≤ 하단 < 상단 ≤ 100 인 숫자여야 합니다.', 'red');
      return;
    }
    if (!bands.some(function (b) { return b.lower === lo && b.upper === up; })) bands.push({ lower: lo, upper: up });
    bands.sort(function (a, b) { return a.lower - b.lower || a.upper - b.upper; });
    $('#bandLower, #bandUpper').val('');
    renderBands();
    schedule();
  });
  $(document).on('click', '.band-x', function () {
    bands.splice($(this).data('i'), 1);
    renderBands();
    schedule();
  });

  // ------------------------------------------------------------ 카드 열고 닫기 · 켜짐 표시
  function refreshCards() {
    $('.ind-card').each(function () {
      var on = $(this).find('.ind-head [data-path$=".enabled"]').prop('checked');
      $(this).toggleClass('off', !on);
    });
  }
  $(document).on('click', '.ind-head', function () { $(this).closest('.ind-card').toggleClass('open'); });
  $('#openAll').on('click', function () {
    var allOpen = $('.ind-card:not(.open)').length === 0;
    $('.ind-card').toggleClass('open', !allOpen);
    $(this).text(allOpen ? '모두 펼치기' : '모두 접기');
  });

  // ------------------------------------------------------------ 원본 정보
  function renderRaw() {
    var id = $('#rawSelect').val();
    $('#rawLink').attr('href', '/raw/' + id);
    var list = INIT.rawQuality[id] || [];
    var $box = $('#rawInfo').empty();
    if (!list.length) return;
    var $t = $('<table class="db-table db-table--compact"><thead><tr><th>거래쌍</th><th class="num">행 수</th><th>첫 봉 ~ 마지막 봉 (KST)</th>' +
      '<th class="num"><span data-term="gap">빈 1분</span></th><th class="num"><span data-term="segment">구간 수</span></th><th class="num">가장 긴 구간</th><th>검사</th></tr></thead><tbody></tbody></table>');
    list.forEach(function (q) {
      var $tr = $('<tr></tr>');
      $tr.append($('<td></td>').html('<b>' + App.esc(q.assetCode) + '</b> <span class="mono">' + App.esc(q.symbol) + '</span>'));
      $tr.append($('<td class="num"></td>').text(App.formatNumber(q.rows)));
      $tr.append($('<td class="num small"></td>').text(App.toKst(q.first) + ' ~ ' + App.toKst(q.last)));
      $tr.append($('<td class="num"></td>').text(App.formatNumber(q.missingMinutes) + '분'));
      $tr.append($('<td class="num"></td>').text(q.segments));
      $tr.append($('<td class="num"></td>').text(App.formatNumber(q.longestSegment) + '행'));
      var $s = $('<td></td>');
      if (q.blockers.length) $s.append(App.tag('생성 차단', 'red')).append($('<div class="small"></div>').text(q.blockers.join(' ')));
      else if (q.warnings.length) $s.append(App.tag('확인 ' + q.warnings.length + '건', 'yellow').attr('title', q.warnings.join('\n')));
      else $s.append(App.tag('이상 없음', 'green'));
      $tr.append($s);
      $t.find('tbody').append($tr);
    });
    $box.append($('<div class="db-table-wrap"></div>').append($t));
    $box.append('<div class="small muted" style="margin-top:4px">BTC · ETH 각각에 같은 설정을 적용하고, 거래쌍마다 따로 계산합니다. 빈 1분 뒤에는 지표를 새로 시작합니다.</div>');
  }
  $('#rawSelect').on('change', function () { renderRaw(); schedule(); });

  // ------------------------------------------------------------ 미리보기
  var schedule = App.debounce(plan, 250);

  function plan() {
    refreshCards();
    $('#previewState').text('계산 중…');
    App.api('POST', '/api/features/plan', { config: read(), rawId: Number($('#rawSelect').val()) }, true)
      .done(render)
      .fail(function (xhr) {
        $('#previewState').text('');
        $('#messages').html('<li class="ERROR">⛔ ' + App.esc((xhr.responseJSON && xhr.responseJSON.message) || '미리보기 실패') + '</li>');
      });
  }

  function render(p) {
    lastPlan = p;
    $('#previewState').text('');
    Glossary.addColumns(p.columns);

    // 메시지
    var $m = $('#messages').empty();
    p.messages.forEach(function (msg) {
      var icon = msg.level === 'ERROR' ? '⛔' : msg.level === 'WARN' ? '⚠️' : 'ℹ️';
      $('<li></li>').addClass(msg.level).text(icon + ' ' + msg.text).appendTo($m);
    });

    // 지표별 카드 · 머리 숫자 · 묶음 숫자
    var $cards = $('#kindCards').empty();
    Object.keys(KIND_LABEL).forEach(function (k) {
      var n = p.byKind[k] || 0;
      var planned = p.bundles.filter(function (b) { return b.kind === k; }).reduce(function (s, b) { return s + b.planned; }, 0);
      $('[data-kind-count="' + k + '"]').text(n + '열').toggleClass('tag-blue', n > 0);
      var $c = $('<div class="card-stat"></div>');
      $c.append($('<div class="label"></div>').text(KIND_LABEL[k]));
      $c.append($('<div class="value"></div>').text(n));
      $c.append($('<div class="sub"></div>').text(planned !== n ? '중복 제거 전 ' + planned : (n ? '고유 열' : '꺼짐 또는 0개')));
      $cards.append($c);
    });
    $('.bcount').text('').removeClass('has');
    p.bundles.forEach(function (b) {
      $('.bcount[data-bundle="' + b.kind + ':' + b.bundle + '"]').text(b.added === b.planned ? b.added : b.added + ' (' + b.planned + ')').toggleClass('has', b.added > 0);
    });

    // 과거 이력
    var $h = $('#historyBox').empty();
    if (p.history.length) {
      var $t = $('<table class="db-table db-table--compact"><thead><tr><th>거래쌍</th><th class="num">행 수</th><th class="num">구간 수</th><th class="num">가장 긴 구간</th><th class="num">최대 워밍업</th><th><span data-term="history">과거 이력</span></th></tr></thead><tbody></tbody></table>');
      p.history.forEach(function (h) {
        $t.find('tbody').append($('<tr></tr>')
          .append($('<td></td>').text(h.label))
          .append($('<td class="num"></td>').text(App.formatNumber(h.rows)))
          .append($('<td class="num"></td>').text(h.segments))
          .append($('<td class="num"></td>').text(App.formatNumber(h.longestSegment) + '행'))
          .append($('<td class="num"></td>').text(p.maxWarmup + '행'))
          .append($('<td></td>').append(h.enough ? App.tag('충분', 'green') : App.tag('부족 — 일부 열이 거의 null', 'red'))));
      });
      $h.append('<div class="small muted" style="margin-bottom:4px">선택한 가장 긴 기간뿐 아니라 RSI 초기 변화량 · EMA/MACD 신호선 초기화 · StochRSI 범위 · 비교 간격까지 더한 값이 최대 워밍업입니다. 열마다 첫 유효 행이 다릅니다 (아래 목록의 워밍업).</div>')
        .append($('<div class="db-table-wrap"></div>').append($t));
    }

    // 중복
    var $d = $('#dupBox').empty();
    if (p.duplicates.length) {
      var $det = $('<details class="toggle"><summary><span class="arrow">▶</span><span></span></summary><div class="toggle-body small"></div></details>');
      $det.find('summary span:last').html('같은 값이라 하나만 남긴 열 <b>' + p.duplicates.length + '</b>개 <span data-term="dedup"></span>');
      p.duplicates.slice(0, 300).forEach(function (x) {
        $det.find('.toggle-body').append($('<div></div>').html('<span class="mono">' + App.esc(x.name) + '</span> → <span class="mono">' + App.esc(x.sameAs) + '</span> 로 저장'));
      });
      $d.append($det);
    }

    // 아래 고정 요약
    $('#sumUnique').text(App.formatNumber(p.columnCount));
    $('#sumPlanned').text(App.formatNumber(p.plannedCount));
    $('#sumWarmup').text(App.formatNumber(p.maxWarmup));
    renderCols();
    updateButton();
  }

  function renderCols() {
    if (!lastPlan) return;
    var q = $.trim($('#colSearch').val()).toLowerCase(), kind = $('#colKind').val();
    var list = lastPlan.columns.filter(function (c) {
      return (!kind || c.kind === kind) && (!q || (c.name + ' ' + c.title + ' ' + c.formula).toLowerCase().indexOf(q) >= 0);
    });
    $('#colCount').text(list.length === lastPlan.columns.length ? list.length + '개' : list.length + ' / ' + lastPlan.columns.length + '개');
    var html = list.slice(0, 1500).map(function (c) {
      return '<tr><td class="num faint">' + (c.index + 1) + '</td>' +
        '<td class="mono"><span class="hover-name" data-hover-term="col_' + App.esc(c.name) + '">' + App.esc(c.name) + '</span></td>' +
        '<td>' + App.esc(c.title) + '</td><td class="small">' + App.esc(c.kindLabel) + '</td>' +
        '<td class="small muted">' + App.esc(c.params) + '</td><td class="num">' + c.warmup + '</td>' +
        '<td class="formula">' + App.esc(c.formula) + '</td></tr>';
    }).join('');
    if (list.length > 1500) html += '<tr><td colspan="7" class="muted small">… 앞의 1,500개만 보여줍니다. 검색으로 좁히세요.</td></tr>';
    $('#colBody').html(html || '<tr><td colspan="7" class="muted small">만들 열이 없습니다.</td></tr>');
  }
  $('#colSearch').on('input', App.debounce(renderCols, 150));
  $('#colKind').on('change', renderCols);

  function updateButton() {
    var problem = !lastPlan ? '미리보기를 계산하는 중입니다.'
      : !lastPlan.ok ? '오류를 먼저 고치세요.'
      : !$.trim($('#name').val()) ? '이름을 입력하세요.' : '';
    var raw = INIT.rawQuality[$('#rawSelect').val()] || [];
    if (!problem && raw.some(function (q) { return q.blockers.length; })) problem = '원본 입력 검사에 생성 차단 문제가 있습니다.';
    $('#createBtn').prop('disabled', !!problem || running);
    $('#sumProblem').text(problem).toggleClass('text-danger', !!problem);
  }

  // ------------------------------------------------------------ 이벤트
  $(document).on('change', '#featureForm input[type=checkbox], #featureForm input[type=radio]', schedule);
  $(document).on('numset:change', '.numset', schedule);
  $('#name').on('input', updateButton);

  $('#resetDefaults').on('click', function () {
    if (!window.confirm('모든 지표 설정을 노션 예시값으로 되돌릴까요?')) return;
    apply(JSON.parse(JSON.stringify(INIT.defaults)));
    schedule();
  });

  function loadPresets() {
    App.api('GET', '/api/feature-presets', null, true).done(function (list) {
      var $s = $('#presetSelect');
      $s.find('option:not(:first)').remove();
      list.forEach(function (p) {
        $('<option></option>').val(p.id).text(p.name + ' (' + App.toKst(p.createdAt).slice(0, 10) + ')').data('config', p.config).appendTo($s);
      });
    });
  }
  $('#presetSelect').on('change', function () {
    var cfg = $(this).find(':selected').data('config');
    if (cfg) { apply(cfg); schedule(); App.toast('저장한 설정을 불러왔습니다.'); }
    $(this).val('');
  });
  $('#savePreset').on('click', function () {
    App.api('POST', '/api/feature-presets', { name: $.trim($('#presetName').val()), config: read() }).done(function () {
      App.toast('설정을 저장했습니다.');
      $('#presetName').val('');
      loadPresets();
    });
  });

  $('#createBtn').on('click', function () {
    running = true;
    updateButton();
    $('#createBtn').text('만드는 중…');
    App.api('POST', '/api/features', { name: $.trim($('#name').val()), rawDatasetId: Number($('#rawSelect').val()), config: read() })
      .done(function (res) {
        $('html, body').animate({ scrollTop: $('#jobBox').offset().top - 60 }, 300);
        App.watchJob($('#jobBox'), res.jobId, function (job) {
          if (job.status === 'SUCCEEDED') window.location.href = '/features/' + res.id;
          else { running = false; $('#createBtn').text('지표 데이터셋 만들기'); updateButton(); }
        });
      })
      .fail(function () { running = false; $('#createBtn').text('지표 데이터셋 만들기'); updateButton(); });
  });

  // ------------------------------------------------------------ 시작
  $('.numset').each(function () { NumSet.init($(this)); });
  apply(INIT.config);
  if (!$.trim($('#name').val())) {
    var rawText = $('#rawSelect option:selected').text().replace(/^#\d+\s*/, '').replace(/\s*\(.*\)$/, '');
    $('#name').val(rawText + ' · 지표');
  }
  renderRaw();
  loadPresets();
  plan();
});
