/* 학습 · 검증 · 최종 테스트 분할 만들기 */
$(function () {
  if (!$('#splitForm').length) return;
  var INIT = window.SPLIT_INIT;
  var lastOk = false;
  var FIELDS = ['useFrom', 'useTo', 'trainFrom', 'trainTo', 'valFrom', 'valTo', 'testFrom', 'testTo'];

  function info() { return INIT.labels[$('#labelSelect').val()]; }

  function config() {
    var c = {};
    FIELDS.forEach(function (f) { c[f] = $('#' + f).val() || null; });
    c.embargoMinutes = Number($('#embargo').val() || 0);
    return c;
  }

  function apply(c) {
    FIELDS.forEach(function (f) { $('#' + f).val(c[f] || ''); });
    $('#embargo').val(c.embargoMinutes || 0);
  }

  function renderInfo() {
    var l = info();
    if (!l) return;
    $('#labelInfo').html('지표 <b>' + App.esc(l.featureName) + '</b> · 원본 ' + App.esc(l.rawName) + ' · ' + App.esc((l.markets || []).join(', ')) +
      ' · 데이터 UTC ' + App.utcDate(l.from) + ' ~ ' + App.utcLastDate(l.to) +
      ' · H ' + l.horizon + '분 · P ' + l.target + '% · L ' + l.stop + '% · 탐색 끝 ' + App.utcDate(l.exploreTo));
    var min = App.utcDate(l.from), max = App.utcLastDate(l.to);
    FIELDS.forEach(function (f) { $('#' + f).attr('min', min).attr('max', max); });
    $('#embargoHint').text('0 이면 label_end_time 검사만 합니다. 고정 여유를 둔다면 H + 진입 지연 정도 (이 정답: ' + (l.horizon + 1) + '분).');
  }

  /** 처음 값: 최종 테스트 = 정답 화면의 숨김 기간. 학습 · 검증 날짜는 비워 두고 직접 고르게 한다. */
  function suggest() {
    var l = info();
    if (!l) return;
    apply({ testFrom: App.utcDate(l.exploreTo), testTo: App.utcLastDate(l.to), embargoMinutes: 0 });
  }

  var preview = App.debounce(function () {
    var l = info(), c = config();
    SplitView.timeline($('#timeline'), $('#tlFrom'), $('#tlTo'), l.from, l.to, c);
    var missing = ['trainFrom', 'trainTo', 'valFrom', 'valTo', 'testFrom', 'testTo'].filter(function (f) { return !c[f]; });
    $('#testHint').text(c.testFrom && c.testFrom < App.utcDate(l.exploreTo)
      ? '⚠️ 정답 화면의 탐색 기간 끝(' + App.utcDate(l.exploreTo) + ')보다 앞에서 시작합니다. 이 앞부분의 정답 분포는 이미 봤을 수 있습니다.'
      : '선택을 끝낸 뒤 한 번 평가하는 기간 (검증 뒤)');
    if (missing.length) {
      lastOk = false;
      $('#problems').html('<li class="INFO">ℹ️ 학습 · 검증 · 최종 테스트의 시작일과 종료일을 모두 고르면 미리보기가 나옵니다.</li>');
      $('#previewBox').empty();
      refresh();
      return;
    }
    $('#previewState').text('계산 중…');
    App.api('POST', '/api/splits/preview', { labelSetId: Number($('#labelSelect').val()), config: c }, true).done(function (s) {
      lastOk = true;
      $('#problems').empty();
      SplitView.render($('#previewBox'), s, null);
    }).fail(function (xhr) {
      lastOk = false;
      $('#previewBox').empty();
      $('#problems').html('<li class="ERROR">⛔ ' + App.esc((xhr.responseJSON && xhr.responseJSON.message) || '미리보기 실패') + '</li>');
    }).always(function () { $('#previewState').text(''); refresh(); });
  }, 300);

  function refresh() {
    var p = !lastOk ? '날짜 문제를 먼저 고치세요.' : !$.trim($('#name').val()) ? '이름을 입력하세요.' : '';
    $('#createBtn').prop('disabled', !!p);
    $('#createHint').text(p);
  }

  function autoName() {
    var c = config();
    if ($('#name').data('touched')) return;
    $('#name').val(c.trainFrom && c.testTo ? '학습 ' + c.trainFrom + '~ · 검증 ' + (c.valFrom || '?') + '~ · 테스트 ' + (c.testFrom || '?') + '~' : '');
  }

  $('#labelSelect').on('change', function () { renderInfo(); suggest(); autoName(); preview(); });
  $(document).on('change input', '#splitForm input[type=date], #embargo', function () { autoName(); preview(); });
  $('#name').on('input', function () { $(this).data('touched', true); refresh(); });

  $('#createBtn').on('click', function () {
    var $b = $(this).prop('disabled', true).text('저장 중…');
    App.api('POST', '/api/splits', {
      name: $.trim($('#name').val()), description: $.trim($('#description').val()),
      labelSetId: Number($('#labelSelect').val()), config: config()
    }).done(function (res) { window.location.href = '/splits/' + res.id; })
      .fail(function () { $b.text('분할 계획 저장'); refresh(); });
  });

  renderInfo();
  if (INIT.config) apply(INIT.config); else suggest();
  if ($.trim($('#name').val())) $('#name').data('touched', true);
  autoName();
  preview();
});
