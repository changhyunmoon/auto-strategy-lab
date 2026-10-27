/* 데이터세트 상세 화면 */
$(function () {
  var $page = $('#detailPage');
  var id = $page.data('id');
  var PAGE_SIZE = 20;
  var page = 0, total = 0;

  // ---------- 만드는 중이면 진행률을 갱신하고, 끝나면 새로고침 ----------
  if (String($page.data('building')) === 'true' && $page.data('job')) {
    (function poll() {
      App.api('GET', '/api/jobs/' + $page.data('job')).done(function (job) {
        $('#progressBar').css('width', job.progress + '%');
        $('#progressText').text(App.jobText(job));
        if (job.status === 'RUNNING') setTimeout(poll, 2000);
        else location.reload();
      });
    })();
  }

  // ---------- 삭제 ----------
  $('#deleteBtn').on('click', function () {
    if (!window.confirm('「' + $page.data('name') + '」을(를) 지울까요? 데이터세트 행이 모두 삭제됩니다. (받아 둔 1분봉 원본은 남습니다)')) return;
    App.api('DELETE', '/api/datasets/' + id).done(function () { location.href = '/datasets'; });
  });

  // ---------- 미리보기 ----------
  function fmt(v) {
    if (v === null || v === undefined) return '';
    var a = Math.abs(v);
    if (a >= 1000) return App.formatNumber(Math.round(v * 100) / 100);
    if (a === 0) return '0';
    return a < 0.001 ? v.toExponential(3) : String(Math.round(v * 100000) / 100000);
  }

  function loadPreview() {
    var params = {
      assetId: $('input[name="pvAsset"]:checked').val(),
      interval: $('input[name="pvInterval"]:checked').val(),
      warmup: $('#pvWarmup').is(':checked'),
      page: page,
      size: PAGE_SIZE
    };
    App.api('GET', '/api/datasets/' + id + '/rows', params).done(function (res) {
      total = res.total;
      var $t = $('#previewTable').empty();
      var $head = $('<tr></tr>').append('<th>시각 (KST)</th>');
      res.columns.forEach(function (c) { $head.append($('<th class="num mono"></th>').text(c)); });
      $t.append($('<thead></thead>').append($head));
      var $body = $('<tbody></tbody>');
      res.rows.forEach(function (r) {
        var $tr = $('<tr></tr>').append($('<td class="num"></td>').text(r.time).append(r.warmup ? ' <span class="tag">워밍업</span>' : ''));
        r.values.forEach(function (v) { $tr.append($('<td class="num"></td>').text(fmt(v))); });
        $body.append($tr);
      });
      $t.append($body);
      var start = total === 0 ? 0 : page * PAGE_SIZE + 1;
      $('#pvInfo').text(start + '–' + Math.min(total, (page + 1) * PAGE_SIZE) + ' / ' + App.formatNumber(total) + '행 · ' + res.columns.length + '열');
      $('#pvPrev').prop('disabled', page === 0);
      $('#pvNext').prop('disabled', (page + 1) * PAGE_SIZE >= total);
    });
  }

  var loaded = false;
  $(document).on('tab:shown', function (_, name) {
    if (name === 'preview' && !loaded) { loaded = true; loadPreview(); }
  });
  $('input[name="pvAsset"], input[name="pvInterval"], #pvWarmup').on('change', function () { page = 0; loadPreview(); });
  $('#pvPrev').on('click', function () { if (page > 0) { page--; loadPreview(); } });
  $('#pvNext').on('click', function () { page++; loadPreview(); });
});
