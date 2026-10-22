/* 노션 스타일에 맞춘 ECharts 테마. echarts 로드 후 불러온다. 사용: echarts.init(el, 'clean') */
(function () {
  if (typeof echarts === 'undefined') return;

  var text = '#37352f', secondary = '#787774', line = '#e9e9e7', faint = '#f1f1ef';

  echarts.registerTheme('clean', {
    color: ['#2383e2', '#d9730d', '#0f7b6c', '#9065b0', '#c14c8a', '#64473a', '#dfab01', '#787774'],
    backgroundColor: 'transparent',
    textStyle: { fontFamily: 'Pretendard, -apple-system, "Malgun Gothic", sans-serif', color: text },
    title: { textStyle: { color: text, fontSize: 14, fontWeight: 600 } },
    legend: { textStyle: { color: secondary, fontSize: 12 }, itemWidth: 14, itemHeight: 8, icon: 'roundRect' },
    tooltip: {
      backgroundColor: '#ffffff', borderColor: line, borderWidth: 1,
      textStyle: { color: text, fontSize: 12 },
      extraCssText: 'box-shadow:0 6px 18px rgba(15,15,15,.08);border-radius:6px;'
    },
    categoryAxis: {
      axisLine: { lineStyle: { color: line } }, axisTick: { show: false },
      axisLabel: { color: secondary, fontSize: 11 }, splitLine: { show: false }
    },
    valueAxis: {
      axisLine: { show: false }, axisTick: { show: false },
      axisLabel: { color: secondary, fontSize: 11 },
      splitLine: { lineStyle: { color: faint } }
    },
    timeAxis: {
      axisLine: { lineStyle: { color: line } }, axisTick: { show: false },
      axisLabel: { color: secondary, fontSize: 11 }, splitLine: { show: false }
    },
    candlestick: {
      itemStyle: { color: '#e03e3e', color0: '#2383e2', borderColor: '#e03e3e', borderColor0: '#2383e2' }
    },
    dataZoom: {
      backgroundColor: 'transparent', borderColor: line,
      fillerColor: 'rgba(35,131,226,.08)', handleStyle: { color: '#fff', borderColor: '#b9b9b5' },
      dataBackground: { lineStyle: { color: '#d3d3cf' }, areaStyle: { color: faint } },
      textStyle: { color: secondary, fontSize: 11 }
    },
    visualMap: { textStyle: { color: secondary } }
  });
})();
