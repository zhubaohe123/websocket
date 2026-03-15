const WebSocket = require('ws');
const crypto = require('crypto');

const PORT = process.env.PORT || 8080;
const wss = new WebSocket.Server({ port: PORT });

// ==================== Data Stores ====================
const rooms = new Map();       // roomId -> Room
const clients = new Map();     // ws -> ClientInfo

class Room {
  constructor(hostId, hostNickname, password, videoUrl) {
    this.id = this.generateRoomId();
    this.password = password;
    this.hostId = hostId;
    this.controllerId = hostId; // current controller
    this.members = new Map();   // clientId -> { nickname, ws }
    this.members.set(hostId, { nickname: hostNickname, ws: null });
    this.state = {
      action: 'pause',
      position: 0,
      speed: 1.0,
      timestamp: Date.now(),
      videoUrl: videoUrl || '' // Store the playing video URL
    };
  }

  generateRoomId() {
    let id;
    do {
      id = String(Math.floor(10000000 + Math.random() * 90000000));
    } while (rooms.has(id));
    return id;
  }

  getMemberList() {
    const list = [];
    for (const [id, info] of this.members) {
      list.push({ id, nickname: info.nickname, isHost: id === this.hostId, isController: id === this.controllerId });
    }
    return list;
  }
}

// ==================== Helpers ====================
function generateClientId() {
  return crypto.randomBytes(8).toString('hex');
}

function generatePassword() {
  return String(Math.floor(100000 + Math.random() * 900000));
}

function sendTo(ws, msg) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(msg));
  }
}

function broadcastToRoom(room, msg, excludeId = null) {
  for (const [id, info] of room.members) {
    if (id !== excludeId && info.ws) {
      sendTo(info.ws, msg);
    }
  }
}

// ==================== Message Handlers ====================
function handleCreateRoom(ws, clientInfo, data) {
  const password = generatePassword();
  const room = new Room(clientInfo.id, data.nickname || '主机', password, data.videoUrl);
  room.members.get(clientInfo.id).ws = ws;

  rooms.set(room.id, room);
  clientInfo.roomId = room.id;
  clientInfo.nickname = data.nickname || '主机';

  sendTo(ws, {
    type: 'room_created',
    roomId: room.id,
    password: password,
    members: room.getMemberList()
  });

  console.log(`Room ${room.id} created by ${clientInfo.nickname} with video: ${data.videoUrl ? 'yes' : 'no'}`);
}

function handleJoinRoom(ws, clientInfo, data) {
  const room = rooms.get(data.roomId);
  if (!room) {
    return sendTo(ws, { type: 'error', message: '房间不存在' });
  }
  if (room.password !== data.password) {
    return sendTo(ws, { type: 'error', message: '密码错误' });
  }
  if (room.members.size >= 10) {
    return sendTo(ws, { type: 'error', message: '房间已满（最多10人）' });
  }

  const nickname = data.nickname || '好友';
  room.members.set(clientInfo.id, { nickname, ws });
  clientInfo.roomId = room.id;
  clientInfo.nickname = nickname;

  // Send room info to the new member
  sendTo(ws, {
    type: 'room_joined',
    roomId: room.id,
    hostNickname: room.members.get(room.hostId)?.nickname || '主机',
    members: room.getMemberList(),
    state: room.state
  });

  // Notify others
  broadcastToRoom(room, {
    type: 'member_joined',
    id: clientInfo.id,
    nickname: nickname,
    members: room.getMemberList()
  }, clientInfo.id);

  console.log(`${nickname} joined room ${room.id}`);
}

function handleLeaveRoom(ws, clientInfo) {
  const room = rooms.get(clientInfo.roomId);
  if (!room) return;

  const nickname = clientInfo.nickname;
  room.members.delete(clientInfo.id);
  clientInfo.roomId = null;

  // If host left, dissolve the room
  if (room.hostId === clientInfo.id || room.members.size === 0) {
    broadcastToRoom(room, { type: 'room_dissolved', message: '主机已离开，房间已解散' });
    rooms.delete(room.id);
    console.log(`Room ${room.id} dissolved`);
  } else {
    // If controller left, transfer to host
    if (room.controllerId === clientInfo.id) {
      room.controllerId = room.hostId;
    }
    broadcastToRoom(room, {
      type: 'member_left',
      id: clientInfo.id,
      nickname: nickname,
      members: room.getMemberList()
    });
  }
}

function handleSync(ws, clientInfo, data) {
  const room = rooms.get(clientInfo.roomId);
  if (!room) return;

  // Only controller can sync
  if (room.controllerId !== clientInfo.id) {
    return sendTo(ws, { type: 'error', message: '你没有控制权' });
  }

  // Update room state
  room.state = {
    action: data.action || room.state.action,
    position: data.position !== undefined ? data.position : room.state.position,
    speed: data.speed !== undefined ? data.speed : room.state.speed,
    videoUrl: data.videoUrl !== undefined ? data.videoUrl : room.state.videoUrl,
    timestamp: Date.now()
  };

  // Log URL transmission for debugging
  if (data.videoUrl) {
    console.log(`[SYNC] Room ${room.id}: Received videoUrl from ${clientInfo.nickname}`);
    console.log(`[SYNC] URL length: ${data.videoUrl.length}`);
    console.log(`[SYNC] Has auth_key: ${data.videoUrl.includes('auth_key')}`);
    console.log(`[SYNC] Broadcasting to ${room.members.size - 1} other members`);
  }

  // Broadcast to all others
  broadcastToRoom(room, {
    type: 'sync',
    action: room.state.action,
    position: room.state.position,
    speed: room.state.speed,
    videoUrl: room.state.videoUrl,
    timestamp: room.state.timestamp,
    fromNickname: clientInfo.nickname
  }, clientInfo.id);
}

function handleRequestState(ws, clientInfo) {
  const room = rooms.get(clientInfo.roomId);
  if (!room) return;
  sendTo(ws, {
    type: 'state_update',
    state: room.state,
    members: room.getMemberList()
  });
}

function handleControlRequest(ws, clientInfo, data) {
  const room = rooms.get(clientInfo.roomId);
  if (!room) return;

  switch (data.action) {
    case 'request': {
      // Send request to host
      const hostInfo = room.members.get(room.hostId);
      if (hostInfo && hostInfo.ws) {
        sendTo(hostInfo.ws, {
          type: 'control_request',
          fromId: clientInfo.id,
          fromNickname: clientInfo.nickname
        });
      }
      sendTo(ws, { type: 'info', message: '已向主机发送控制权请求' });
      break;
    }
    case 'grant': {
      // Only host can grant
      if (room.hostId !== clientInfo.id) {
        return sendTo(ws, { type: 'error', message: '只有主机可以授予控制权' });
      }
      const targetId = data.targetId;
      if (!room.members.has(targetId)) {
        return sendTo(ws, { type: 'error', message: '目标用户不在房间中' });
      }
      room.controllerId = targetId;
      broadcastToRoom(room, {
        type: 'control_changed',
        controllerId: targetId,
        controllerNickname: room.members.get(targetId)?.nickname || '',
        members: room.getMemberList()
      });
      break;
    }
    case 'revoke': {
      // Only host can revoke
      if (room.hostId !== clientInfo.id) {
        return sendTo(ws, { type: 'error', message: '只有主机可以收回控制权' });
      }
      room.controllerId = room.hostId;
      broadcastToRoom(room, {
        type: 'control_changed',
        controllerId: room.hostId,
        controllerNickname: room.members.get(room.hostId)?.nickname || '主机',
        members: room.getMemberList()
      });
      break;
    }
  }
}

// ==================== WebSocket Connection ====================
wss.on('connection', (ws) => {
  const clientId = generateClientId();
  const clientInfo = { id: clientId, roomId: null, nickname: '', lastPing: Date.now() };
  clients.set(ws, clientInfo);

  sendTo(ws, { type: 'connected', clientId });
  console.log(`Client connected: ${clientId}`);

  ws.on('message', (raw) => {
    let data;
    try {
      data = JSON.parse(raw.toString());
    } catch (e) {
      return sendTo(ws, { type: 'error', message: '无效的消息格式' });
    }

    clientInfo.lastPing = Date.now();

    switch (data.type) {
      case 'create_room':
        handleCreateRoom(ws, clientInfo, data);
        break;
      case 'join_room':
        handleJoinRoom(ws, clientInfo, data);
        break;
      case 'leave_room':
        handleLeaveRoom(ws, clientInfo);
        break;
      case 'sync':
        handleSync(ws, clientInfo, data);
        break;
      case 'request_state':
        handleRequestState(ws, clientInfo);
        break;
      case 'control':
        handleControlRequest(ws, clientInfo, data);
        break;
      case 'ping':
        sendTo(ws, { type: 'pong', timestamp: Date.now() });
        break;
      default:
        sendTo(ws, { type: 'error', message: `未知消息类型: ${data.type}` });
    }
  });

  ws.on('close', () => {
    console.log(`Client disconnected: ${clientId}`);
    if (clientInfo.roomId) {
      handleLeaveRoom(ws, clientInfo);
    }
    clients.delete(ws);
  });

  ws.on('error', (err) => {
    console.error(`Client error ${clientId}:`, err.message);
  });
});

// ==================== Heartbeat ====================
const HEARTBEAT_INTERVAL = 30000;
const HEARTBEAT_TIMEOUT = 60000;

setInterval(() => {
  const now = Date.now();
  for (const [ws, info] of clients) {
    if (now - info.lastPing > HEARTBEAT_TIMEOUT) {
      console.log(`Client ${info.id} timed out`);
      ws.terminate();
    }
  }
}, HEARTBEAT_INTERVAL);

// ==================== Startup ====================
console.log(`SyncWatch signaling server running on port ${PORT}`);
console.log(`WebSocket endpoint: ws://0.0.0.0:${PORT}`);
