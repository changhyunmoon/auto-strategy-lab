/* 데이터세트 만들기 화면: 받아 둔 원본(1분봉)으로 간격 · 지표를 계산한다 */
$(function () {
  var STEPS = ['재집계', '지표 계산', '품질 검사', '완료'];
  var BAR_COLUMNS = 6;
  var DAY = 86400000;
  var maxDate = $('#datasetForm').data('max-date');
  var warmupByCode = {};
  (window.DATASET_FEATURES || []).forEach(function (f) { warmupByCode[f.code] = f.warmup || {}; });
  var coverage = null; // /api/raw/coverage 결과
  var running = false;
  var rawOk = false;

  function selected(name) {
    return $('input[name="' + name + '"]:checked').map(function () { return this.value; }).get();
  }

  function selectedFeatures() {
    return $('.check-row:not(.is-disabled) input:checked').map(function () { return this.value; }).get();
  }

  // ---------- 지표 조건: 필요한 원본이 없으면 비활성화 ----------
  function applyRequirements() {
    var sources = selected('source'), assets = selected('asset');
    var ok = {
      BINANCE_CANDLES: sources.indexOf('BINANCE') >= 0,
      BOTH_EXCHANGES: sources.indexOf('BINANCE') >= 0 && sources.indexOf('UPBIT') >= 0,
      BOTH_ASSETS: assets.length === 2
    };
    $('.check-row').each(function () {
      var reqs = String($(this).data('requires') || '').split(',').filter(Boolean);
      var enabled = reqs.every(function (r) { return ok[r]; });
      $(this).toggleClass('is-disabled', !enabled);
      $(this).find('input').prop('disabled', !enabled);
      $(this).find('.need').html(enabled ? '' : '<span class="tag tag-yellow"></span>');
      if (!enabled) $(this).find('.need .tag').text($(this).data('need'));
    });
  }

  function updateGroups() {
    var total = 0, cols = 0;
    $('#featureGroups details').each(function () {
      var $rows = $(this).find('.check-row:not(.is-disabled)');
      var $on = $rows.filter(function () { return $(this).find('input').prop('checked'); });
      $(this).find('.toggle-meta').text($on.length + '/' + $(this).find('.check-row').length + ' 선택');
      var $g = $(this).find('.group-check');
      $g.prop('checked', $on.length === $rows.length && $on.length > 0);
      $g.prop('indeterminate', $on.length > 0 && $on.length < $rows.length);
      total += $on.length;
      $on.each(function () { cols += Number($(this).data('cols')); });
    });
    $('#featureCount').text(total + '개 선택');
    return cols;
  }

  function days() {
    var a = new Date($('#dateFrom').val()), b = new Date($('#dateTo').val());
    return Math.max(1, Math.round((b - a) / DAY) + 1);
  }

  /** 서버와 같은 규칙: 고른 지표 × 간격 중 가장 긴 워밍업(최소 1시간)을 시간 단위로 올림 (분) */
  function warmupMinutes() {
    var max = 60, intervals = selected('interval');
    selectedFeatures().forEach(function (code) {
      intervals.forEach(function (iv) { max = Math.max(max, Number((warmupByCode[code] || {})[iv] || 0)); });
    });
    return Math.ceil(max / 60) * 60;
  }

  function warmupText(minutes) {
    if (minutes < 1440) return (minutes / 60) + '시간';
    return minutes % 1440 === 0 ? (minutes / 1440) + '일' : (minutes / 1440).toFixed(1) + '일';
  }

  /** 필요한 원본 기간 [from, to) (epoch ms) */
  function neededRange() {
    var from = Date.parse($('#dateFrom').val() + 'T00:00:00Z') - warmupMinutes() * 60000;
    var to = Date.parse($('#dateTo').val() + 'T00:00:00Z') + DAY;
    return { from: from, to: to };
  }

  /** 받아 둔 구간들(시간순)로 [from, to) 중 빠진 구간 */
  function missingRanges(ranges, from, to) {
    var out = [], cursor = from;
    ranges.forEach(function (r) {
      var a = Date.parse(r.from), b = Date.parse(r.to);
      if (cursor >= to || b <= cursor || a >= to) return;
      if (a > cursor) out.push({ from: cursor, to: a });
      cursor = Math.max(cursor, b);
    });
    if (cursor < to) out.push({ from: cursor, to: to });
    return out;
  }

  function utcDate(ms) { return new Date(ms).toISOString().slice(0, 10); }
  function rangeText(r) { return utcDate(r.from) + ' ~ ' + utcDate(r.to - 1); }

  // ---------- 원본 준비 상태 ----------
  function updateRawStatus() {
    if (!coverage) return;
    var $box = $('#rawStatus').empty();
    var need = neededRange(), assets = selected('asset'), sources = selected('source');
    $('#rawNeed').text('필요한 원본 기간 (UTC): ' + rangeText(need) + ' — 앞쪽 ' + warmupText(warmupMinutes())
      + '은 지표 계산용(워밍업)입니다. 칩에 마우스를 올리면 빠진 구간이 보입니다.');
    var missingAll = [];
    assets.forEach(function (assetId) {
      sources.forEach(function (ex) {
        var m = coverage.filter(function (c) { return String(c.assetId) === String(assetId) && c.exchange === ex; })[0];
        if (!m) return;
        var miss = missingRanges(m.ranges || [], need.from, need.to);
        $('<span class="tag"></span>').addClass(miss.length ? 'tag-yellow' : 'tag-green')
          .text(m.assetCode + ' ' + m.exchangeLabel + (miss.length ? ' · 부족' : ' · 준비됨'))
          .attr('title', miss.length ? '빠진 구간: ' + miss.map(rangeText).join(', ') : '필요한 기간을 모두 받아 두었습니다.')
          .appendTo($box);
        if (miss.length) missingAll.push(m.assetCode + ' ' + m.exchangeLabel + ' — 빠진 구간 ' + miss.map(rangeText).join(', '));
      });
    });
    if (!$box.children().length) $box.html('<span class="muted small">종목과 원본을 고르세요.</span>');
    rawOk = missingAll.length === 0;
    $('#rawMissing').toggle(!rawOk);
    if (!rawOk) {
      $('#rawMissingText').html(missingAll.map(function (t) { return $('<div>').text(t).html(); }).join('<br>'));
      var q = $.param({ assets: assets.join(','), exchanges: sources.join(','), from: utcDate(need.from), to: utcDate(need.to - 1) });
      $('#rawMissingLink').attr('href', '/collect?' + q);
    }
  }

  function updateSummary() {
    applyRequirements();
    var cols = updateGroups();
    var nAssets = selected('asset').length, d = days();
    var perMinute = selected('interval').reduce(function (s, v) { return s + 1 / parseInt(v, 10); }, 0);
    var rows = Math.round(nAssets * d * 1440 * perMinute);
    var minutes = Math.max(1, Math.round(d / 20 + rows / 400000));
    $('#sumRows').text(App.formatNumber(rows));
    $('#sumCols').text(BAR_COLUMNS + cols);
    $('#sumWarmup').text(warmupText(warmupMinutes()));
    $('#sumTime').text('약 ' + minutes + '분');
    updateRawStatus();

    var problem = !nAssets ? '종목을 하나 이상 고르세요.'
      : !selected('source').length ? '사용할 원본을 하나 이상 고르세요.'
      : !selected('interval').length ? '봉 간격을 하나 이상 고르세요.'
      : $('#dateFrom').val() > $('#dateTo').val() ? '시작일이 종료일보다 늦습니다.'
      : !$.trim($('#datasetName').val()) ? '데이터세트 이름을 입력하세요.'
      : !coverage ? '받아 둔 원본을 확인하는 중입니다.'
      : !rawOk ? '받아 둔 원본이 모자랍니다. 먼저 원본을 받아 주세요.' : '';
    $('#submitBtn').prop('disabled', !!problem || running);
    $('#submitHint').text(problem);
  }

  // ---------- 받아 둔 원본 ----------
  App.api('GET', '/api/raw/coverage').done(function (list) {
    coverage = list;
    updateSummary();
  });

  // ---------- 입력 이벤트 ----------
  function autoName() {
    var codes = $('input[name="asset"]:checked').map(function () { return $(this).data('code'); }).get().join('·');
    var label = { 7: '7일', 30: '1개월', 182: '6개월', 730: '24개월' }[$('input[name="quick"]:checked').val()] || (days() + '일');
    $('#datasetName').val((codes || '종목') + ' ' + label + ' 지표');
  }

  $('input[name="quick"]').on('change', function () {
    var to = new Date(maxDate);
    var from = new Date(to.getTime() - (Number(this.value) - 1) * DAY);
    $('#dateTo').val(String(maxDate));
    $('#dateFrom').val(from.toISOString().slice(0, 10));
    autoName();
    updateSummary();
  });
  $('#dateFrom, #dateTo').on('change', function () { $('input[name="quick"]').prop('checked', false); });
  $('input[name="asset"]').on('change', autoName);

  $('.group-check').on('click', function (e) {
    e.stopPropagation();
    $(this).closest('details').find('.check-row:not(.is-disabled) input').prop('checked', this.checked);
    updateSummary();
  });
  $('#checkAll, #uncheckAll').on('click', function () {
    $('.check-row:not(.is-disabled) input').prop('checked', this.id === 'checkAll');
    updateSummary();
  });
  $(document).on('change input', '#datasetForm input', updateSummary);

  // ---------- 만들기 → 진행률 ----------
  $('#submitBtn').on('click', function () {
    var body = {
      name: $.trim($('#datasetName').val()),
      assetIds: selected('asset').map(Number),
      from: $('#dateFrom').val(),
      to: $('#dateTo').val(),
      sources: selected('source'),
      intervals: selected('interval'),
      features: selectedFeatures()
    };
    running = true;
    $('#submitBtn').prop('disabled', true).text('만드는 중…');
    App.api('POST', '/api/datasets', body)
      .done(function (res) {
        $('#progressBox').show();
        $('#failBox, #doneBox').hide();
        $('#doneLink').attr('href', '/datasets/' + res.datasetId);
        $('#cancelBtn').attr('data-cancel-job', res.jobId).prop('disabled', false).text('■ 중지').show();
        $('html, body').animate({ scrollTop: $('#progressBox').offset().top - 60 }, 300);
        poll(res.jobId);
      })
      .fail(function () {
        running = false;
        $('#submitBtn').text('만들기');
        updateSummary();
      });
  });

  function poll(jobId) {
    App.api('GET', '/api/jobs/' + jobId).done(function (job) {
      renderProgress(job);
      if (job.status === 'RUNNING') {
        setTimeout(function () { poll(jobId); }, 2000);
      } else {
        running = false;
        $('#cancelBtn').hide();
        $('#submitBtn').text('만들기');
        updateSummary();
      }
    });
  }

  function renderProgress(job) {
    var p = job.progress, done = job.status === 'SUCCEEDED', failed = job.status === 'FAILED';
    var now = done ? STEPS.length - 1 : Math.max(0, STEPS.indexOf(job.step));
    $('#progressBar').css('width', p + '%');
    $('#progressText').text(App.jobText(job));
    $('#steps li').each(function (i) {
      var isDone = done || i < now, isNow = !done && i === now;
      $(this).toggleClass('done', isDone).toggleClass('now', isNow && !failed);
      $(this).find('.mark').text(isDone ? '✓' : isNow ? (failed ? '✕' : '●') : '○');
      $(this).find('.step-meta').text(isNow && !failed ? (job.message || '') : '');
    });
    if (done) {
      $('#progressTag').attr('class', 'tag tag-green').html('<span class="dot"></span>완료');
      $('#doneBox').show();
    } else if (failed) {
      $('#progressTag').attr('class', 'tag tag-red').html('<span class="dot"></span>실패');
      $('#failText').text(job.message || '알 수 없는 오류');
      $('#failBox').show();
    } else {
      $('#progressTag').attr('class', 'tag tag-blue').html('<span class="dot"></span>진행 중');
    }
  }

  // ---------- 같은 설정으로 새로 만들기 (?copy=데이터세트ID) ----------
  var copyId = new URLSearchParams(location.search).get('copy');
  if (copyId) {
    App.api('GET', '/api/datasets/' + copyId).done(function (d) {
      $('input[name="asset"]').each(function () { this.checked = d.assetIds.indexOf(Number(this.value)) >= 0; });
      $('input[name="source"]').each(function () { this.checked = d.sources.indexOf(this.value) >= 0; });
      $('input[name="interval"]').each(function () { this.checked = d.intervals.indexOf(this.value) >= 0; });
      $('input[name="feature"]').each(function () { this.checked = d.featureCodes.indexOf(this.value) >= 0; });
      $('input[name="quick"]').prop('checked', false);
      $('#dateFrom').val(d.fromDate);
      $('#dateTo').val(d.toDate > String(maxDate) ? String(maxDate) : d.toDate);
      $('#datasetName').val(d.summary.name + ' (다시)');
      updateSummary();
    });
  }

  // ---------- 원본 데이터 받기에서 넘어온 값 (?assets=1,2&sources=UPBIT,BINANCE&from=&to=) ----------
  var q = new URLSearchParams(location.search);
  if (!copyId && q.get('from') && q.get('to')) {
    var qAssets = (q.get('assets') || '').split(','), qSources = (q.get('sources') || '').split(',');
    if (q.get('assets')) $('input[name="asset"]').each(function () { this.checked = qAssets.indexOf(this.value) >= 0; });
    if (q.get('sources')) $('input[name="source"]').each(function () { this.checked = qSources.indexOf(this.value) >= 0; });
    $('#dateFrom').val(q.get('from'));
    $('#dateTo').val(q.get('to') > String(maxDate) ? String(maxDate) : q.get('to'));
    $('input[name="quick"]').prop('checked', false);
    autoName();
  }

  updateSummary();
});
