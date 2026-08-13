/** 实时地图（Leaflet + OpenStreetMap）：当前定位、轨迹线、户节点、Home。断网时瓦片缺失但轨迹/节点仍正常绘制。 */

import L from 'leaflet';

export interface MapController {
  /** 跟随当前位置（含自车标记） */
  follow(lat: number, lng: number): void;
  /** 整条轨迹线 */
  setTrack(pts: { lat: number; lng: number }[]): void;
  /** 户节点 + Home 标记 */
  setNodes(
    home: { lat: number; lng: number },
    nodes: { id: string; name: string; autoNo: number; pos: { lat: number; lng: number } }[],
  ): void;
  destroy(): void;
}

export function mountMap(
  el: HTMLElement,
  initial: { lat: number; lng: number } | null,
): MapController {
  const map = L.map(el, {
    zoomControl: true,
    attributionControl: true,
    center: initial ? [initial.lat, initial.lng] : [36.71, 119.1], // 默认潍坊附近
    zoom: initial ? 17 : 13,
  });
  map.attributionControl.setPrefix(false);
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '© OpenStreetMap',
  }).addTo(map);

  const track = L.polyline([], {
    color: '#c8402f',
    weight: 4,
    opacity: 0.85,
    lineJoin: 'round',
  }).addTo(map);
  const trackPts: L.LatLngExpression[] = [];

  let me: L.CircleMarker | null = null;
  let homeMarker: L.Marker | null = null;
  let nodeLayer = L.layerGroup().addTo(map);

  function follow(lat: number, lng: number): void {
    if (!me) {
      me = L.circleMarker([lat, lng], {
        radius: 9,
        color: '#ffffff',
        weight: 2.5,
        fillColor: '#c8402f',
        fillOpacity: 1,
      }).addTo(map);
    } else {
      me.setLatLng([lat, lng]);
    }
    trackPts.push([lat, lng]);
    track.setLatLngs(trackPts);
    map.setView([lat, lng], Math.max(map.getZoom(), 16), { animate: true });
  }

  function setTrack(pts: { lat: number; lng: number }[]): void {
    trackPts.length = 0;
    for (const p of pts) trackPts.push([p.lat, p.lng]);
    track.setLatLngs(trackPts);
  }

  function setNodes(
    home: { lat: number; lng: number },
    nodes: { id: string; name: string; autoNo: number; pos: { lat: number; lng: number } }[],
  ): void {
    nodeLayer.clearLayers();
    if (homeMarker) {
      homeMarker.remove();
      homeMarker = null;
    }
    homeMarker = L.marker([home.lat, home.lng], {
      icon: L.divIcon({
        className: 'baibai-home-icon',
        html: '<div class="home-pin">家</div>',
        iconSize: [30, 30],
        iconAnchor: [15, 15],
      }),
      zIndexOffset: 200,
    }).addTo(map);
    for (const n of nodes) {
      const m = L.marker([n.pos.lat, n.pos.lng], {
        icon: L.divIcon({
          className: 'baibai-node-icon',
          html: `<div class="node-pin">${n.autoNo}</div>`,
          iconSize: [26, 26],
          iconAnchor: [13, 13],
        }),
        zIndexOffset: 150,
      });
      if (n.name) m.bindTooltip(n.name, { direction: 'top', offset: [0, -14] });
      nodeLayer.addLayer(m);
    }
  }

  function destroy(): void {
    map.remove();
  }

  return { follow, setTrack, setNodes, destroy };
}
