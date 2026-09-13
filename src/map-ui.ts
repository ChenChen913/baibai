/** 实时地图（Leaflet）：高德瓦片（普通地图 + 卫星，免 Key，国内秒开）+ OSM 兜底。
 * WGS-84 定位 → GCJ-02 再渲染（高德瓦片是火星坐标）；断网时纸色底，轨迹/标记照常绘制。 */

import L from 'leaflet';
import { wgs2gcj } from './wgs2gcj.js';

export interface MapController {
  /** 跟随当前位置（自车标记 + 精度圈 + 动画平滑跟随） */
  follow(lat: number, lng: number, accM?: number): void;
  /** 整条轨迹线（分段：走过淡红、当前段实红粗线，像导航一样）。
   *  fit=true 时才自动缩放到全轨迹（默认 false——避免每次暂停/继续都抢走用户视野） */
  setTrack(pts: { lat: number; lng: number }[], breaks?: number[], fit?: boolean): void;
  /** E-2：追加一个"已入库"轨迹点（状态机过滤闸门之后），实时增长当前段 */
  appendTrackPoint(pos: { lat: number; lng: number }): void;
  /** 户节点 + Home 标记 */
  setNodes(
    home: { lat: number; lng: number },
    nodes: { id: string; name: string; autoNo: number; pos: { lat: number; lng: number } }[],
  ): void;
  /** 定位回中 */
  recenter(): void;
  /** 适应全轨迹视野 */
  fitBounds(): void;
  /** 切换图层（街道 / 卫星） */
  switchTileLayer(to?: 'street' | 'sat'): 'street' | 'sat';
  /** 高亮/飞往某户 */
  focusNode(no: number | string): void;
  /** 容器尺寸变化后重算（抽屉展开/折叠后调用） */
  invalidateSize(): void;
  destroy(): void;
}

export function mountMap(
  el: HTMLElement,
  initial: { lat: number; lng: number } | null,
): MapController {
  const map = L.map(el, {
    zoomControl: false, // 手机双指缩放，不显示遮挡画面的 +/- 控件
    attributionControl: false, // 版权注由地图卡或面板展示
    center: initial ? [initial.lat, initial.lng] : [36.7095, 118.9118], // 默认潍坊昌乐附近
    zoom: initial ? 16 : 14,
  });

  /* ---------- 图层：普通地图（高德 → OSM 兜底）+ 卫星（高德 → Esri 兜底） ---------- */
  let streetSrc = 0; // 0 = 高德（GCJ-02），1 = OSM（WGS-84）
  let satSrc = 0; // 0 = 高德（GCJ-02），1 = Esri（WGS-84）
  let streetErr = 0;
  let satErr = 0;
  let street: L.TileLayer | null = null;
  let sat: L.TileLayer | null = null;
  let curLayer: 'street' | 'sat' = 'street';
  // E-2：自动跟随开关——用户手动拖动地图后暂停跟随（按「定位回中」恢复），
  // 否则每个 GPS fix 都把视野拉回当前位置，记录过程中无法查看整体路线
  let autoFollow = true;

  map.on('dragstart', () => {
    autoFollow = false;
  });

  const makeStreet = (): void => {
    const isGaode = streetSrc === 0;
    const layer = L.tileLayer(
      isGaode
        ? 'https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}'
        : 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
      { maxZoom: 19, ...(isGaode ? { subdomains: '1234' } : {}) },
    );
    layer.on('tileerror', () => {
      streetErr += 1;
      if (streetErr > 6 && streetSrc === 0) {
        streetSrc = 1; // 降级 OSM（WGS-84）——坐标系由 cvt 按图层源判定
        streetErr = 0;
        layer.remove();
        makeStreet();
        redrawAll();
      }
    });
    street = layer;
    if (curLayer === 'street') layer.addTo(map);
  };

  const makeSat = (): void => {
    const isGaode = satSrc === 0;
    const layer = L.tileLayer(
      isGaode
        ? 'https://webst0{s}.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}'
        : // E-13：卫星层补兜底源（对齐安卓 map.html 的 Esri World Imagery）——
          // 修复前卫星图层只有高德一个源，被风控/断网时不显示任何影像
          'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
      { maxZoom: 19, ...(isGaode ? { subdomains: '1234' } : {}) },
    );
    layer.on('tileerror', () => {
      satErr += 1;
      if (satErr > 6 && satSrc === 0) {
        satSrc = 1;
        satErr = 0;
        layer.remove();
        makeSat();
        redrawAll();
      }
    });
    sat = layer;
    if (curLayer === 'sat') layer.addTo(map);
  };
  makeStreet();
  makeSat();

  const cvt = (p: [number, number]): [number, number] => {
    // E-13：坐标系按"当前显示图层的实际源"判定（与安卓 map.html 同口径）。
    // 修复前用全局 useGcj：卫星层降级到 Esri(WGS-84) 后仍在做 GCJ 转换，
    // 而且切图层不会切换坐标系 → 轨迹/标记整体偏移约 500m
    const needGcj = curLayer === 'street' ? streetSrc === 0 : satSrc === 0;
    return needGcj ? wgs2gcj(p[0], p[1]) : p;
  };

  /* ---------- 轨迹（分段） ---------- */
  const trackPts: [number, number][] = [];
  const segBreaks: number[] = [0];
  const segGroup = L.layerGroup().addTo(map);

  function drawSegments(): void {
    segGroup.clearLayers();
    for (let i = 0; i < segBreaks.length; i++) {
      const a = segBreaks[i];
      const b = i + 1 < segBreaks.length ? segBreaks[i + 1] : trackPts.length;
      if (b - a < 2) continue;
      const pts: L.LatLngExpression[] = [];
      for (let k = a; k < b; k++) pts.push(cvt(trackPts[k]));
      const isCurrent = i === segBreaks.length - 1;
      L.polyline(pts, {
        color: '#c8402f',
        weight: isCurrent ? 5 : 4,
        opacity: isCurrent ? 0.95 : 0.45,
        lineCap: 'round',
        lineJoin: 'round',
      }).addTo(segGroup);
    }
  }

  /* ---------- 当前位置 + 精度圈 + 呼吸脉冲圈 ---------- */
  let meMarker: L.Marker | null = null;
  let accCircle: L.Circle | null = null;
  let lastPos: [number, number] | null = initial ? [initial.lat, initial.lng] : null;

  function drawMe(lat: number, lng: number, accM?: number): void {
    const c = cvt([lat, lng]);
    if (!meMarker) {
      const liveIcon = L.divIcon({
        className: 'baibai-me-pin-wrap',
        html: `
          <div class="relative w-8 h-8 flex items-center justify-center pointer-events-none">
            <div class="absolute w-8 h-8 bg-red-500 rounded-full opacity-40 pulse-dot"></div>
            <div class="w-4 h-4 bg-red-600 border-2 border-white rounded-full shadow-lg"></div>
          </div>
        `,
        iconSize: [32, 32],
        iconAnchor: [16, 16],
      });
      meMarker = L.marker(c, { icon: liveIcon, zIndexOffset: 500 }).addTo(map);
    } else {
      meMarker.setLatLng(c);
    }

    if (accCircle) {
      accCircle.remove();
      accCircle = null;
    }
    if (accM && accM > 0) {
      accCircle = L.circle(c, {
        radius: accM,
        color: '#c8402f',
        weight: 1,
        fillColor: '#c8402f',
        fillOpacity: 0.08,
        interactive: false,
      }).addTo(map);
    }
  }

  /* ---------- 家/户标记 ---------- */
  let rawHome: { lat: number; lng: number } | null = null;
  let rawNodes: { name: string; autoNo: number; pos: { lat: number; lng: number } }[] = [];
  let nodeLayer = L.layerGroup().addTo(map);

  function drawNodes(): void {
    const layer = L.layerGroup();
    if (rawHome && (rawHome.lat !== 0 || rawHome.lng !== 0)) {
      const homeIcon = L.divIcon({
        className: 'baibai-home-icon',
        html: `
          <div class="w-7 h-7 bg-amber-500 border-2 border-white rounded-full flex items-center justify-center text-white text-[11px] font-black shadow-md">
            <svg class="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/></svg>
          </div>
        `,
        iconSize: [28, 28],
        iconAnchor: [14, 14],
      });
      L.marker(cvt([rawHome.lat, rawHome.lng]), {
        icon: homeIcon,
        zIndexOffset: 300,
      }).addTo(layer).bindPopup('起点：自家庭院');
    }

    for (const n of rawNodes) {
      const isLatest = rawNodes.length > 0 && n.autoNo === rawNodes[rawNodes.length - 1].autoNo;
      const nodeIcon = L.divIcon({
        className: 'baibai-node-icon',
        html: isLatest
          ? `<div class="relative w-8 h-8 flex items-center justify-center">
              <div class="absolute w-8 h-8 bg-red-500 rounded-full opacity-40 pulse-dot"></div>
              <div class="w-6 h-6 bg-red-600 border-2 border-white rounded-full shadow-lg flex items-center justify-center text-white text-[10px] font-black">${n.autoNo}</div>
            </div>`
          : `<div class="w-5 h-5 bg-white border-2 border-[#C8402F] rounded-full flex items-center justify-center text-[#C8402F] text-[10px] font-black shadow-sm">${n.autoNo}</div>`,
        iconSize: isLatest ? [32, 32] : [22, 22],
        iconAnchor: isLatest ? [16, 16] : [11, 11],
      });

      const m = L.marker(cvt([n.pos.lat, n.pos.lng]), {
        icon: nodeIcon,
        zIndexOffset: isLatest ? 350 : 200,
      });

      const label = document.createElement('div');
      label.className = 'text-xs font-bold text-stone-800';
      label.textContent = `${n.autoNo}. ${n.name || '拜访点'}`;
      m.bindTooltip(label, { direction: 'top', offset: [0, -12] });
      layer.addLayer(m);
    }

    nodeLayer.remove();
    nodeLayer = layer.addTo(map);
  }

  function redrawAll(): void {
    drawSegments();
    drawNodes();
    if (lastPos) drawMe(lastPos[0], lastPos[1]);
  }

  /**
   * 相机跟随 + 当前位置标记（E-2：不再往轨迹里塞点）。
   * 修复前 follow() 无条件 trackPts.push(原始 fix)，绕过状态机的六道过滤闸门——
   * 坐着不动地图上照样画抖动线，按「暂停」后又被 syncMap 重建纠正，前后不一致。
   * 现在轨迹只由 appendTrackPoint（已入库点）与 setTrack（快照重建）驱动。
   */
  function follow(lat: number, lng: number, accM?: number): void {
    lastPos = [lat, lng];
    drawMe(lat, lng, accM);
    if (autoFollow) {
      map.setView(cvt([lat, lng]), Math.max(map.getZoom(), 16), { animate: true });
    }
  }

  /** E-2：追加一个"已入库"轨迹点（状态机过滤之后），实时增长当前段 */
  function appendTrackPoint(pos: { lat: number; lng: number }): void {
    trackPts.push([pos.lat, pos.lng]);
    if (segBreaks.length === 0) segBreaks.push(0);
    drawSegments();
  }

  function setTrack(pts: { lat: number; lng: number }[], breaks?: number[], fit = false): void {
    trackPts.length = 0;
    for (const p of pts) trackPts.push([p.lat, p.lng]);
    segBreaks.length = 0;
    if (breaks && breaks.length > 0) segBreaks.push(...breaks);
    else segBreaks.push(0);
    drawSegments();
    // E-2：不再每次重建都 fitBounds（那会在每次暂停/继续时抢走用户的视野），
    // 只由工具栏「总览全路线」显式触发
    if (fit && trackPts.length > 1) {
      map.fitBounds(L.latLngBounds(trackPts.map((p) => cvt(p))).pad(0.25));
    }
  }

  function setNodes(
    home: { lat: number; lng: number },
    nodes: { id: string; name: string; autoNo: number; pos: { lat: number; lng: number } }[],
  ): void {
    rawHome = home;
    rawNodes = nodes;
    drawNodes();
  }

  function recenter(): void {
    autoFollow = true; // E-2：手动回中即恢复自动跟随
    if (lastPos) {
      map.flyTo(cvt(lastPos), Math.max(map.getZoom(), 16), { animate: true, duration: 0.8 });
    } else if (rawHome && (rawHome.lat !== 0 || rawHome.lng !== 0)) {
      map.flyTo(cvt([rawHome.lat, rawHome.lng]), 16, { animate: true, duration: 0.8 });
    }
  }

  function fitBounds(): void {
    const ptsToFit: [number, number][] = [];
    if (trackPts.length > 0) ptsToFit.push(...trackPts);
    if (rawHome && (rawHome.lat !== 0 || rawHome.lng !== 0)) ptsToFit.push([rawHome.lat, rawHome.lng]);
    rawNodes.forEach((n) => ptsToFit.push([n.pos.lat, n.pos.lng]));

    if (ptsToFit.length > 1) {
      const bounds = L.latLngBounds(ptsToFit.map((p) => cvt(p)));
      map.flyToBounds(bounds, { padding: [60, 60], animate: true, duration: 0.8 });
    } else if (ptsToFit.length === 1) {
      map.flyTo(cvt(ptsToFit[0]), 16, { animate: true, duration: 0.8 });
    }
  }

  function switchTileLayer(to?: 'street' | 'sat'): 'street' | 'sat' {
    if (to) {
      curLayer = to;
    } else {
      curLayer = curLayer === 'street' ? 'sat' : 'street';
    }

    if (curLayer === 'street') {
      if (sat && map.hasLayer(sat)) map.removeLayer(sat);
      if (street && !map.hasLayer(street)) street.addTo(map);
    } else {
      if (street && map.hasLayer(street)) map.removeLayer(street);
      if (sat && !map.hasLayer(sat)) sat.addTo(map);
    }
    // E-13：两个图层的源不同 → 坐标系不同（高德 GCJ-02 / OSM、Esri WGS-84），
    // 切换后必须整体重投影，否则轨迹与标记会偏移约 500m
    redrawAll();
    return curLayer;
  }

  function focusNode(no: number | string): void {
    const target = rawNodes.find((n) => n.autoNo === Number(no));
    if (target) {
      map.flyTo(cvt([target.pos.lat, target.pos.lng]), 17, { animate: true, duration: 0.8 });
    }
  }

  function destroy(): void {
    map.remove();
  }

  function invalidateSize(): void {
    map.invalidateSize();
  }

  return {
    follow,
    setTrack,
    appendTrackPoint,
    setNodes,
    recenter,
    fitBounds,
    switchTileLayer,
    focusNode,
    invalidateSize,
    destroy,
  };
}

