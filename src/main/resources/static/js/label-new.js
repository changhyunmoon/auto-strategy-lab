/* H·P·L 정답 만들기 */
$(function () {
  if (!$('#labelForm').length) return;
  var INIT = window.LABEL_INIT;
  var running = false;
  var DEFAULT = { horizon: 30, targetPct: 0.5, stopPct: 0.5, direction: 'LONG', entry: 'NEXT_OPEN', sameBar: 'AMBIGUOUS', costEnabled: false, feePct: 0.05, slippagePct: 0.02 };

  function info() {
    return INIT.features[$('#featureSelect').val()];
  }

  function config() {
    return {
      horizon: Number($('#horizon').val()),
      targetPct: Number($('#target').val()),
      stopPct: Number($('#stop').val()),
      direction: $('input[name=direction]:checked').val(),
      entry: $('input[name=entry]:checked').val(),
      sameBar: $('input[name=sameBar]:checked').val(),
      costEnabled: $('#costEnabled').prop('checked'),
      feePct: Number($('#fee').val() || 0),
      slippagePct: Number($('#slippage').val() || 0)
    };
  }

  function apply(c) {
    $('#horizon').val(c.horizon);
    $('#target').val(c.targetPct);
    $('#stop').val(c.stopPct);
    $('input[name=direction][value=' + c.direction + ']').prop('checked', true);
    $('input[name=entry][value=' + c.entry + ']').prop('checked', true);
    $('input[name=sameBar][value=' + c.sameBar + ']').prop('checked', true);
    $('#costEnabled').prop('checked', !!c.costEnabled);
    $('#fee').val(c.feePct);
    $('#slippage').val(c.slippagePct);
  }

  function problem() {
    var c = config();
    if (!(c.horizon >= 1 && c.horizon === Math.floor(c.horizon) && c.horizon <= 10080)) return 'H 는 1 이상 10,080 이하의 정수(분)여야 합니다.';
    if (!(c.targetPct > 0)) return 'P 는 0보다 큰 % 값이어야 합니다.';
    if (!(c.stopPct > 0 && c.stopPct < 100)) return 'L 은 0보다 크고 100 미만이어야 합니다.';
    if (c.costEnabled && !(c.stopPct - (c.feePct + c.slippagePct) * 2 > 0)) return '비용을 반영하면 L 이 왕복 비용보다 커야 합니다.';
    if (!$('#exploreTo').val()) return '탐색 기간 끝 날짜를 고르세요.';
    var f = info();
    if (f) {
      var d = $('#exploreTo').val();
      if (d <= App.utcDate(f.from) || d > App.utcDate(f.to)) return '탐색 기간 끝은 데이터 기간 안이어야 합니다.';
    }
    return '';
  }

  function refresh() {
    var c = config();
    $('#ruleBox').text(LabelView.ruleText(c));
    $('.cost-fields').css('opacity', c.costEnabled ? 1 : 0.45);
    var rt = (c.feePct + c.slippagePct) * 2;
    $('#costHint').text(c.costEnabled
      ? '왕복 비용 ' + rt.toFixed(3) + '% → 확인할 목표 폭 ' + (c.targetPct + rt).toFixed(3) + '%, 손실 폭 ' + (c.stopPct - rt).toFixed(3) + '%. 호가 · 체결을 재현하지 않는 추정값입니다.'
      : '끄면 가격 경로만으로 판정합니다. 켜면 목표 폭 = P + 왕복 비용, 손실 폭 = L − 왕복 비용으로 확인합니다 (호가 · 체결을 재현하지 않는 추정값).');
    var f = info(), d = $('#exploreTo').val();
    if (f && d) {
      var total = App.daysBetween(App.utcDate(f.from), App.utcDate(f.to));
      var explore = App.daysBetween(App.utcDate(f.from), d);
      $('#exploreInfo').text('탐색 ' + explore + '일 · 숨김 ' + (total - explore) + '일 (전체 ' + total + '일)');
    }
    var p = problem();
    $('#createBtn').prop('disabled', !!p || running || !$.trim($('#name').val()));
    $('#previewBtn').prop('disabled', !!p);
    $('#createHint').text(p || (!$.trim($('#name').val()) ? '이름을 입력하세요.' : ''));
  }

  function renderInfo() {
    var f = info();
    if (!f) return;
    $('#featureInfo').html('원본 <b>' + App.esc(f.rawName) + '</b> · ' + App.esc(f.exchange) + ' · ' + App.esc((f.markets || []).join(', ')) +
      ' · UTC ' + App.utcDate(f.from) + ' ~ ' + App.utcLastDate(f.to) + ' · ' + App.formatNumber(f.rows) + '행');
    $('#exploreTo').attr('min', App.addDays(App.utcDate(f.from), 1)).attr('max', App.utcDate(f.to));
  }

  function defaultExplore() {
    var f = info();
    if (!f) return;
    var from = App.utcDate(f.from), to = App.utcDate(f.to);
    var total = App.daysBetween(from, to);
    $('#exploreTo').val(App.addDays(from, Math.max(1, Math.round(total * 0.8))));
  }

  function autoName() {
    var c = config();
    $('#name').val('H' + c.horizon + ' · P' + c.targetPct + ' · L' + c.stopPct + (c.direction === 'SHORT' ? ' · short' : '') + (c.costEnabled ? ' · 비용' : ''));
  }

  $('#featureSelect').on('change', function () {
    renderInfo();
    defaultExplore();
    $('#previewBox').html('<div class="lock-box">설정이 바뀌었습니다. 「미리보기 계산」을 다시 누르세요.</div>');
    refresh();
  });
  $(document).on('input change', '#labelForm input', function (e) {
    if (e.target.id !== 'name' && e.target.id !== 'description') {
      $('#previewBox .db-table-wrap').css('opacity', 0.5);
      if (!$('#name').data('touched')) autoName();
    }
    refresh();
  });
  $('#name').on('input', function () { $(this).data('touched', true); });

  $('#previewBtn').on('click', function () {
    var $b = $(this).prop('disabled', true).text('계산 중…');
    App.api('POST', '/api/labels/preview', {
      featureSetId: Number($('#featureSelect').val()), config: config(), exploreTo: $('#exploreTo').val() + 'T00:00:00Z'
    }).done(function (s) { LabelView.render($('#previewBox'), s); })
      .always(function () { $b.prop('disabled', false).text('미리보기 계산'); refresh(); });
  });

  $('#createBtn').on('click', function () {
    running = true;
    refresh();
    $('#createBtn').text('만드는 중…');
    App.api('POST', '/api/labels', {
      name: $.trim($('#name').val()), description: $.trim($('#description').val()),
      featureSetId: Number($('#featureSelect').val()), config: config(), exploreTo: $('#exploreTo').val() + 'T00:00:00Z'
    }).done(function (res) {
      $('html, body').animate({ scrollTop: $('#jobBox').offset().top - 60 }, 300);
      App.watchJob($('#jobBox'), res.jobId, function (job) {
        if (job.status === 'SUCCEEDED') window.location.href = '/labels/' + res.id;
        else { running = false; $('#createBtn').text('정답 만들기'); refresh(); }
      });
    }).fail(function () { running = false; $('#createBtn').text('정답 만들기'); refresh(); });
  });

  apply(INIT.config || DEFAULT);
  renderInfo();
  if (INIT.exploreTo) $('#exploreTo').val(INIT.exploreTo.slice(0, 10)); else defaultExplore();
  if (!$.trim($('#name').val())) autoName(); else $('#name').data('touched', true);
  refresh();
});
