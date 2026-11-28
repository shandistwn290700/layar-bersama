// Live screen casting over WebRTC. The control page sends, each TV receives.
// Signaling ({t:'signal', to/from, data}) rides on the app's existing WebSocket.

const CastConfig = { iceServers: [{ urls: 'stun:stun.l.google.com:19302' }] };

// Runs async handlers one at a time so SDP and ICE messages are applied in order.
function serialQueue() {
  let chain = Promise.resolve();
  return (fn) => (chain = chain.then(fn).catch((e) => console.warn('cast:', e)));
}

const CAST_PROFILES = {
  // Slides and documents: sharp text, fewer frames.
  sharp: { frameRate: 15, maxBitrate: 5_000_000, hint: 'detail', degradation: 'maintain-resolution' },
  // Video and animation: smooth motion.
  smooth: { frameRate: 30, maxBitrate: 8_000_000, hint: 'motion', degradation: 'maintain-framerate' },
};

class CastSender {
  constructor(send, stream, profile) {
    this.send = send;
    this.stream = stream;
    this.profile = CAST_PROFILES[profile] || CAST_PROFILES.sharp;
    this.peers = new Map(); // display id -> { pc, name, queue }
    this.onchange = () => {};
    for (const t of stream.getVideoTracks()) t.contentHint = this.profile.hint;
  }

  addPeer(id, name) {
    this.removePeer(id);
    const pc = new RTCPeerConnection({ iceServers: CastConfig.iceServers });
    const peer = { pc, name, queue: serialQueue() };
    this.peers.set(id, peer);
    pc.onicecandidate = (e) => e.candidate && this.send({ t: 'signal', to: id, data: { candidate: e.candidate } });
    pc.onconnectionstatechange = () => this.onchange();
    for (const track of this.stream.getTracks()) pc.addTrack(track, this.stream);
    peer.queue(async () => {
      await pc.setLocalDescription(await pc.createOffer());
      this.send({ t: 'signal', to: id, data: { sdp: pc.localDescription } });
    });
    this.onchange();
  }

  onSignal(from, data) {
    const peer = this.peers.get(from);
    if (!peer) return;
    peer.queue(async () => {
      if (data.sdp) {
        await peer.pc.setRemoteDescription(data.sdp);
        await this.applyEncoding(peer.pc);
      } else if (data.candidate) {
        await peer.pc.addIceCandidate(data.candidate).catch(() => {});
      }
    });
  }

  async applyEncoding(pc) {
    for (const s of pc.getSenders()) {
      if (!s.track || s.track.kind !== 'video') continue;
      const p = s.getParameters();
      if (!p.encodings || !p.encodings.length) p.encodings = [{}];
      p.encodings[0].maxBitrate = this.profile.maxBitrate;
      p.degradationPreference = this.profile.degradation;
      await s.setParameters(p).catch(() => {});
    }
  }

  // Swap the picture (e.g. front/back camera) on every TV without reconnecting.
  async replaceVideoTrack(track) {
    for (const old of this.stream.getVideoTracks()) { this.stream.removeTrack(old); old.stop(); }
    this.stream.addTrack(track);
    track.contentHint = this.profile.hint;
    // The receiver side of each transceiver always knows its media kind, even when the sender's track is gone.
    await Promise.all([...this.peers.values()].flatMap((p) =>
      p.pc.getTransceivers()
        .filter((t) => t.receiver.track && t.receiver.track.kind === 'video')
        .map((t) => t.sender.replaceTrack(track).catch(() => {}))));
  }

  removePeer(id) {
    const peer = this.peers.get(id);
    if (!peer) return;
    peer.pc.close();
    this.peers.delete(id);
    this.onchange();
  }

  resetPeers() {
    for (const id of [...this.peers.keys()]) this.removePeer(id);
  }

  connectedNames() {
    return [...this.peers.values()].filter((p) => p.pc.connectionState === 'connected').map((p) => p.name);
  }

  stop() {
    this.resetPeers();
    for (const t of this.stream.getTracks()) t.stop();
  }
}

class CastReceiver {
  constructor(send, onStream) {
    this.send = send;
    this.onStream = onStream;
    this.pc = null;
    this.queue = serialQueue();
  }

  onSignal(from, data) {
    this.queue(async () => {
      if (data.sdp && data.sdp.type === 'offer') {
        this.close();
        const pc = (this.pc = new RTCPeerConnection({ iceServers: CastConfig.iceServers }));
        pc.ontrack = (e) => this.onStream(e.streams[0] || new MediaStream([e.track]));
        pc.onicecandidate = (e) => e.candidate && this.send({ t: 'signal', to: from, data: { candidate: e.candidate } });
        await pc.setRemoteDescription(data.sdp);
        await pc.setLocalDescription(await pc.createAnswer());
        // Keep latency low: play frames as soon as they arrive.
        for (const r of pc.getReceivers()) if ('jitterBufferTarget' in r) r.jitterBufferTarget = 0;
        this.send({ t: 'signal', to: from, data: { sdp: pc.localDescription } });
      } else if (data.candidate && this.pc) {
        await this.pc.addIceCandidate(data.candidate).catch(() => {});
      }
    });
  }

  close() {
    if (this.pc) { this.pc.close(); this.pc = null; }
  }
}
