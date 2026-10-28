/* 모든 페이지 공통: API 호출 · 오류 표시 · 숫자/시간 표시 · 작업 진행률 · 숫자 집합 입력 */
var App = (function ($) {

  /** JSON API 호출. 실패하면 화면 오른쪽 아래에 오류를 띄운다. */
  function api(method, url, data, quiet) {
    return $.ajax({
      method: method,
      url: url,
      contentType: 'application/json',
      data: data && method !== 'GET' ? JSON.stringify(data) : data,
      dataType: 'json',
      traditional: true
    }).fail(function (xhr) {
      if (quiet) return;
      var msg = (xhr.responseJSON && xhr.responseJSON.message) || ('요청 실패 (' + xhr.status + ')');
      toast(msg, 'red');
    });
  }

  /** 화면 아래쪽에 잠깐 나타나는 알림. */
  function toast(message, color) {
    var $t = $('<div class="callout callout-' + (color || 'blue') + ' toast-box"></div>').text(message).appendTo('body');
    setTimeout(function () { $t.fadeOut(200, function () { $t.remove(); }); }, color === 'red' ? 6000 : 3000);
  }

  function formatNumber(n) {
    return n == null || n === '' ? '' : Number(n).toLocaleString('ko-KR');
  }

  /** UTC ISO 문자열 → KST 'YYYY-MM-DD HH:mm' */
  function toKst(iso) {
    if (!iso) return '';
    var d = new Date(new Date(iso).getTime() + 9 * 3600 * 1000);
    return d.toISOString().slice(0, 16).replace('T', ' ');
  }

  /** UTC ISO 문자열 → UTC 'YYYY-MM-DD HH:mm' */
  function toUtc(iso) {
    return iso ? new Date(iso).toISOString().slice(0, 16).replace('T', ' ') : '';
  }

  /** UTC ISO → UTC 날짜 'YYYY-MM-DD' */
  function utcDate(iso) {
    return iso ? new Date(iso).toISOString().slice(0, 10) : '';
  }

  /** 끝 제외 경계 ISO → 포함되는 마지막 UTC 날짜 */
  function utcLastDate(iso) {
    return iso ? new Date(new Date(iso).getTime() - 1).toISOString().slice(0, 10) : '';
  }

  /** 'YYYY-MM-DD' 에 일 수 더하기 */
  function addDays(date, days) {
    var d = new Date(date + 'T00:00:00Z');
    d.setUTCDate(d.getUTCDate() + days);
    return d.toISOString().slice(0, 10);
  }

  function daysBetween(a, b) {
    return Math.round((new Date(b + 'T00:00:00Z') - new Date(a + 'T00:00:00Z')) / 86400000);
  }

  /** 0~1 비율 → "12.3%". 값이 없으면 "–" */
  function pct(v, digits) {
    return v == null || isNaN(v) ? '–' : (v * 100).toFixed(digits == null ? 1 : digits) + '%';
  }

  /** a ÷ b → "12.3%" */
  function ratio(a, b, digits) {
    return !b ? '–' : pct(a / b, digits);
  }

  /** 숫자 → 고정 소수 자리. 값이 없으면 "–" */
  function fixed(v, digits) {
    return v == null || isNaN(v) ? '–' : Number(v).toFixed(digits == null ? 3 : digits);
  }

  /** 지표 값처럼 크기가 제각각인 수를 보기 좋게 */
  function smart(v) {
    if (v == null || isNaN(v)) return '';
    var a = Math.abs(v);
    if (a === 0) return '0';
    if (a >= 1e6) return Number(v).toLocaleString('ko-KR', { maximumFractionDigits: 0 });
    if (a >= 100) return Number(v).toLocaleString('ko-KR', { maximumFractionDigits: 2 });
    if (a >= 1) return Number(v).toFixed(4).replace(/0+$/, '').replace(/\.$/, '');
    return Number(v).toPrecision(4);
  }

  /** 상태 태그 (green · red · blue · 회색) */
  function tag(text, color) {
    return $('<span class="tag"><span class="dot"></span></span>').addClass(color ? 'tag-' + color : '').append(document.createTextNode(text));
  }

  var STATUS = { PENDING: ['대기', ''], BUILDING: ['만드는 중', 'blue'], READY: ['완료', 'green'], FAILED: ['실패', 'red'] };

  function statusTag(status) {
    var s = STATUS[status] || [status, ''];
    return tag(s[0], s[1]);
  }

  function esc(s) {
    return $('<div>').text(s == null ? '' : String(s)).html();
  }

  /** 입력이 멈춘 뒤 ms 지나서 한 번 실행 */
  function debounce(fn, ms) {
    var t = null;
    return function () {
      var args = arguments, self = this;
      clearTimeout(t);
      t = setTimeout(function () { fn.apply(self, args); }, ms);
    };
  }

  /** 탭: .tabs 안의 .tab[data-tab] 를 누르면 같은 이름의 .tab-panel[data-panel] 을 보여준다. */
  $(document).on('click', '.tabs .tab', function () {
    var $tab = $(this), name = $tab.data('tab');
    var $scope = $tab.closest('[data-tabs-scope]');
    if (!$scope.length) $scope = $(document.body);
    $tab.addClass('active').siblings().removeClass('active');
    $scope.find('.tab-panel').removeClass('active').filter('[data-panel="' + name + '"]').addClass('active');
    $(document).trigger('tab:shown', [name]);
  });

  /** 「중지」 버튼: data-cancel-job="작업 번호" 를 붙이면 확인 후 그 작업을 멈춘다. */
  $(document).on('click', '[data-cancel-job]', function () {
    var $b = $(this), jobId = $b.attr('data-cancel-job');
    if (!jobId || !window.confirm('진행 중인 작업을 중지할까요?\n지금까지 받은 1분봉은 DB 에 남고, 이 결과물은 「실패(중지)」로 표시됩니다.')) return;
    $b.prop('disabled', true).text('중지하는 중…');
    api('POST', '/api/jobs/' + jobId + '/cancel').done(function () {
      toast('중지를 요청했습니다. 몇 초 안에 멈춥니다.');
    }).fail(function () { $b.prop('disabled', false).text('■ 중지'); });
  });

  /** 「지우기」 버튼: data-delete-url · data-delete-confirm · data-after(이동할 주소) */
  $(document).on('click', '[data-delete-url]', function () {
    var $b = $(this);
    if (!window.confirm($b.data('delete-confirm') || '지울까요? 되돌릴 수 없습니다.')) return;
    api('DELETE', $b.data('delete-url')).done(function () {
      toast('지웠습니다.');
      if ($b.data('after')) window.location.href = $b.data('after');
      else $b.closest('tr').remove();
    });
  });

  /**
   * 작업 진행률 상자. $box 안에 .progress-thin > span, .job-step, .job-text, .job-fail, .job-cancel 이 있으면 채운다.
   * 끝나면 onDone(job) 을 부른다 (성공 · 실패 모두).
   */
  function watchJob($box, jobId, onDone) {
    $box.show();
    $box.find('.job-cancel').attr('data-cancel-job', jobId).prop('disabled', false).text('■ 중지').show();
    function tick() {
      api('GET', '/api/jobs/' + jobId, null, true).done(function (job) {
        $box.find('.progress-thin > span').css('width', job.progress + '%');
        $box.find('.job-step').text(job.step || '');
        $box.find('.job-text').text(job.progress + '% · ' + (job.message || ''));
        if (job.status === 'RUNNING') {
          setTimeout(tick, 1500);
        } else {
          $box.find('.job-cancel').hide();
          if (job.status === 'FAILED') {
            $box.find('.job-fail').show().find('.job-fail-text').text(job.message || '실패했습니다.');
          }
          if (onDone) onDone(job);
        }
      }).fail(function () { setTimeout(tick, 4000); });
    }
    tick();
  }

  return {
    api: api, toast: toast, formatNumber: formatNumber, toKst: toKst, toUtc: toUtc, utcDate: utcDate, utcLastDate: utcLastDate,
    addDays: addDays, daysBetween: daysBetween, pct: pct, ratio: ratio, fixed: fixed, smart: smart, tag: tag,
    statusTag: statusTag, esc: esc, debounce: debounce, watchJob: watchJob
  };
})(jQuery);

/*
 * 숫자 집합 입력: <div class="numset" data-type="int|float" data-min="1"></div>
 * 값은 칩으로 보이고 × 로 지운다. 입력칸에 숫자를 쓰고 Enter · 쉼표 · 띄어쓰기로 추가한다 (여러 개 붙여 넣기 가능).
 * 중복은 빼고 오름차순으로 정렬한다. 값이 바뀌면 'numset:change' 이벤트를 낸다.
 */
var NumSet = (function ($) {
  function render($el) {
    var values = $el.data('values') || [];
    var $chips = $el.find('.numset-chips').empty();
    values.forEach(function (v) {
      $('<span class="numset-chip"></span>').text(v)
        .append($('<button type="button" class="numset-x" aria-label="지우기">×</button>').data('v', v))
        .appendTo($chips);
    });
    if (!values.length) $chips.append('<span class="faint small">없음</span>');
  }

  function init($el) {
    if ($el.data('ns-init')) return;
    $el.data('ns-init', true);
    $el.append('<span class="numset-chips"></span>')
      .append('<input type="text" class="form-control form-control-sm numset-input" placeholder="+ 추가" inputmode="decimal">');
    $el.data('values', []);
    render($el);
  }

  function parse($el, text) {
    var isInt = $el.data('type') !== 'float';
    var min = $el.data('min') != null ? Number($el.data('min')) : (isInt ? 1 : 0);
    var out = [], bad = [];
    String(text).split(/[\s,]+/).filter(Boolean).forEach(function (t) {
      var n = Number(t);
      if (isNaN(n) || (isInt && n !== Math.floor(n)) || n < min || (!isInt && n <= 0)) bad.push(t);
      else out.push(n);
    });
    return { values: out, bad: bad };
  }

  function set($el, values) {
    init($el);
    var uniq = [];
    (values || []).forEach(function (v) { if (uniq.indexOf(Number(v)) < 0) uniq.push(Number(v)); });
    uniq.sort(function (a, b) { return a - b; });
    $el.data('values', uniq);
    render($el);
  }

  function get($el) {
    return ($el.data('values') || []).slice();
  }

  $(document).on('keydown', '.numset-input', function (e) {
    if (e.key !== 'Enter' && e.key !== ',' && e.key !== ' ') return;
    e.preventDefault();
    add($(this));
  });
  $(document).on('blur', '.numset-input', function () { if ($(this).val()) add($(this)); });

  function add($input) {
    var $el = $input.closest('.numset');
    var p = parse($el, $input.val());
    if (p.bad.length) {
      var isInt = $el.data('type') !== 'float';
      App.toast('넣을 수 없는 값: ' + p.bad.join(', ') + (isInt ? ' (양의 정수만 · 단위는 1분봉 개수)' : ' (0보다 큰 수만)'), 'red');
    }
    if (p.values.length) {
      set($el, get($el).concat(p.values));
      $el.trigger('numset:change');
    }
    $input.val('');
  }

  $(document).on('click', '.numset-x', function () {
    var $el = $(this).closest('.numset'), v = $(this).data('v');
    set($el, get($el).filter(function (x) { return x !== v; }));
    $el.trigger('numset:change');
  });

  return { init: init, set: set, get: get };
})(jQuery);
