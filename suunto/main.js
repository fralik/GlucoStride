// Original implementation against SuuntoPlus Editor 1.42.0's documented ES5 API.
var liveProfile = true;
var serviceUuid = [71, 91, 13, 111, 138, 46, 33, 156, 58, 79, 139, 109, 0, 32, 158, 123];
var characteristicUuid = [71, 91, 13, 111, 138, 46, 33, 156, 58, 79, 139, 109, 1, 32, 158, 123];

var uint16 = function(bytes, offset) {
  return bytes[offset] + bytes[offset + 1] * 256;
};

var uint32 = function(bytes, offset) {
  return uint16(bytes, offset) + uint16(bytes, offset + 2) * 65536;
};

var decodePacket = function(bytes) {
  if (!bytes || typeof bytes === 'string' || bytes.length !== 20) return null;
  for (var i = 0; i < 20; i++) {
    if (typeof bytes[i] !== 'number' || bytes[i] < 0 || bytes[i] > 255 ||
        bytes[i] !== Math.floor(bytes[i])) return null;
  }
  var protocol = liveProfile ? 2 : 1;
  if (bytes[0] !== protocol || bytes[1] !== protocol || bytes[2] > 5 || bytes[3] !== 0) return null;
  var glucose = uint16(bytes, 4);
  var trend = uint16(bytes, 6);
  var age = uint32(bytes, 8);
  glucose = glucose === 65535 ? null : glucose;
  trend = trend === 32768 ? null : (trend >= 32768 ? trend - 65536 : trend) / 100;
  age = age === 4294967295 ? null : age;
  if (bytes[2] === 0) {
    if (glucose === null || glucose === 0 || age === null || age >= 600) return null;
    if (liveProfile && uint32(bytes, 12) === 0) return null;
  } else if (glucose !== null || trend !== null) {
    return null;
  }
  return {
    status: bytes[2],
    glucose: glucose,
    trend: trend,
    age: age,
    sample: uint32(bytes, 12),
    sequence: uint32(bytes, 16)
  };
};

// A clock-injected receiver; the adapter below reads the native UTC resource.
var createReceiver = function() {
  return {
    now: null,
    clockFailed: false,
    connected: false,
    packet: null,
    age: null,
    lastRx: null,
    sequence: null,
    reconnectPending: false,
    history: null,
    fault: 6,
    advance: function(now) {
      if (this.clockFailed) return false;
      if (typeof now !== 'number' || !isFinite(now) || now < 0 ||
          (this.now !== null && now < this.now)) {
        this.clockFailed = true;
        this.fault = 9;
        return false;
      }
      if (this.now !== null && this.age !== null) this.age += now - this.now;
      if (this.now !== null && this.history && this.history.age !== null) {
        this.history.age += now - this.now;
      }
      this.now = now;
      return true;
    },
    setConnected: function(connected) {
      if (liveProfile) {
        if (this.connected === connected) return;
        this.reconnectPending = connected;
      }
      if (this.connected && !connected) this.reconnectPending = true;
      this.connected = connected;
      this.lastRx = null;
      this.fault = connected ? 6 : 7;
      // Keep history; a changed sample after disconnection can establish a new session.
    },
    receive: function(bytes, now) {
      if (!this.advance(now) || !this.connected) return false;
      var packet = decodePacket(bytes);
      if (!packet) {
        this.fault = 8;
        return false;
      }
      if (liveProfile) return this.receiveLive(packet, now);
      var rebase = this.reconnectPending && this.packet && this.packet.sample !== packet.sample;
      if (this.sequence !== null && !rebase) {
        var delta = (packet.sequence - this.sequence + 4294967296) % 4294967296;
        if (delta === 0 || delta >= 2147483648) return false;
      }
      if (this.packet && this.packet.sample === packet.sample) {
        if (this.packet.status === 0 && packet.status === 0 &&
            (this.packet.glucose !== packet.glucose || this.packet.trend !== packet.trend)) {
          this.fault = 8;
          return false;
        }
        if (this.age !== null && (packet.age === null || packet.age < this.age)) {
          packet.age = this.age;
        }
      }
      this.packet = packet;
      this.age = packet.age;
      this.sequence = packet.sequence;
      this.reconnectPending = false;
      this.lastRx = now;
      this.fault = null;
      return true;
    },
    receiveLive: function(packet, now) {
      if (this.sequence !== null && !this.reconnectPending) {
        var delta = (packet.sequence - this.sequence + 4294967296) % 4294967296;
        if (delta === 0 || delta >= 2147483648) return false;
      }
      var history = this.history;
      if (packet.sample !== 0) {
        // Epoch sample IDs cannot roll back, even after a phone restart.
        if (history && packet.sample < history.sample) {
          this.fault = 8;
          return false;
        }
        if (history && packet.sample === history.sample) {
          if (history.hasGood && packet.status === 0 &&
              (history.glucose !== packet.glucose || history.trend !== packet.trend)) {
            this.fault = 8;
            return false;
          }
          if (history.age !== null && (packet.age === null || packet.age < history.age)) {
            packet.age = history.age;
          }
        } else {
          history = { sample: packet.sample, age: null, hasGood: false };
        }
        history.age = packet.age;
        if (packet.status === 0) {
          history.hasGood = true;
          history.glucose = packet.glucose;
          history.trend = packet.trend;
        }
        this.history = history;
      }
      // A sample-zero outage suppresses the screen but cannot erase source history.
      this.packet = packet;
      this.age = packet.age;
      this.sequence = packet.sequence;
      this.reconnectPending = false;
      this.lastRx = now;
      this.fault = null;
      return true;
    },
    snapshot: function(now) {
      this.advance(now);
      var status = this.fault;
      if (this.clockFailed) status = 9;
      else if (!this.connected) status = 7;
      else if (status === null) {
        if (this.lastRx === null || now - this.lastRx >= 20) status = 7;
        else if (!this.packet) status = 6;
        else if (this.packet.status === 0 && this.age !== null && this.age >= 600) status = 5;
        else status = this.packet.status;
      }
      return {
        status: status,
        glucose: status === 0 ? this.packet.glucose : null,
        trend: status === 0 ? this.packet.trend : null,
        age: this.clockFailed ? null : this.age
      };
    }
  };
};

var convertGlucose = function(value, unit) {
  return value === null ? -1 : (unit === 1 ? value : value / 18.0);
};

var trendArrow = function(value) {
  if (value === null) return 32768;
  var direction = value < 0 ? -1 : 1;
  var speed = Math.abs(value);
  if (speed >= 3) return direction * 3;
  if (speed >= 2) return direction * 2;
  if (speed >= 1) return direction;
  return 0;
};

var receiver, connectionId, connectionState, registered, configured, unit;
var registrationFailures, configurationFailures, connectFailures, clockStalls;
var clockRequestPending, clockRequestWaits, clockReady, clockErrorStatus;

var failWatchClock = function(status) {
  if (!receiver.clockFailed) {
    clockErrorStatus = status;
    receiver.advance(NaN);
  }
};

var readWatchClock = function(isEvaluation) {
  if (receiver.clockFailed) return;
  if (clockRequestPending) {
    if (isEvaluation && ++clockRequestWaits >= 3) failWatchClock(14);
    return;
  }
  clockRequestPending = true;
  clockRequestWaits = 0;
  // Read the native value instead of relying on a cached numeric manifest input.
  $.get('/Dev/Time', function(now) {
    clockRequestPending = false;
    if (receiver.clockFailed) return;
    if (typeof now !== 'number' || !isFinite(now) || now <= 0) {
      clockReady = false;
      if (receiver.now !== null) failWatchClock(11);
      return;
    }
    if (receiver.now !== null && now < receiver.now) {
      failWatchClock(12);
      return;
    }
    if (now !== receiver.now) clockStalls = 0;
    else if (isEvaluation) clockStalls++;
    if (clockStalls >= 3) {
      failWatchClock(13);
      return;
    }
    receiver.advance(now);
    clockReady = true;
  });
};

var bleEventHandler = function(characteristicId, eventId, data) {
  if (eventId === 100) {
    receiver.setConnected(true);
    configured = false;
    connectionState = registered ? 2 : 1;
    registrationFailures = configurationFailures = connectFailures = 0;
  } else if (eventId === 101) {
    receiver.setConnected(false);
    configured = false;
    connectionState = 99; // The watch owns automatic radio reconnection.
  } else if (eventId === 112) {
    receiver.setConnected(false);
    configured = false;
    connectFailures++;
    connectionState = connectFailures < 3 ? 0 : 99;
  } else if (characteristicId === 0) {
    if (eventId === 107 && receiver.connected) {
      registered = true;
      connectionState = 2;
    } else if (eventId === 109 && receiver.connected) {
      configured = true;
      connectionState = 3;
    } else if (eventId === 108) {
      registered = false;
      registrationFailures++;
      receiver.fault = 7;
      connectionState = receiver.connected && registrationFailures < 3 ? 1 : 99;
    } else if (eventId === 110) {
      configured = false;
      configurationFailures++;
      receiver.fault = 7;
      connectionState = receiver.connected && configurationFailures < 3 ? 2 : 99;
    } else if ((eventId === 102 || eventId === 106) && receiver.connected && configured) {
      // BLE callbacks lack an input argument. The last observed UTC is conservative:
      // using an earlier receipt time can expire data early, never extend its lifetime.
      if (receiver.now !== null) receiver.receive(data, receiver.now);
    } else if (eventId === 103) {
      receiver.fault = 7; // A later valid notification can recover from a failed initial read.
    }
  }
};

var renderOutputs = function(output) {
  output.con = configured && receiver.connected ? 1 : 0;
  if (receiver.clockFailed || receiver.now === null || !clockReady) {
    output.status = receiver.clockFailed ? clockErrorStatus : 10;
    output.glucose = output.age = -1;
    output.trend = 32768;
    return;
  }
  var view = receiver.snapshot(receiver.now);
  output.status = view.status;
  output.glucose = convertGlucose(view.glucose, unit);
  output.trend = trendArrow(view.trend);
  output.age = view.age === null ? -1 : Math.floor(view.age);
  clockReady = false;
};

function onLoad(input, output) {
  receiver = createReceiver();
  connectionId = null;
  connectionState = 0;
  registered = configured = false;
  registrationFailures = configurationFailures = connectFailures = clockStalls = 0;
  clockRequestPending = clockReady = false;
  clockRequestWaits = 0;
  clockErrorStatus = 9;
  unit = typeof localStorage !== 'undefined' && localStorage.getItem('glucoseUnit') === '1' ? 1 : 0;
  output.con = 0;
  output.glucose = output.age = -1;
  output.trend = 32768;
  output.status = 6;
}

var updateWatch = function(output) {
  if (connectionState === 0) {
    connectionState = 99;
    // The official BLE template uses AD type + all 16 UUID bytes (17 total).
    connectionId = appConn.connect(enabledZappId, bleEventHandler,
      [6, 71, 91, 13, 111, 138, 46, 33, 156, 58, 79, 139, 109, 0, 32, 158, 123],
      [7, 71, 91, 13, 111, 138, 46, 33, 156, 58, 79, 139, 109, 0, 32, 158, 123]);
  } else if (connectionState === 1) {
    connectionState = 99;
    appConn.regUuid(connectionId, 0, serviceUuid, characteristicUuid);
  } else if (connectionState === 2) {
    connectionState = 99;
    appConn.enaCharNotf(connectionId, 0);
  } else if (connectionState === 3) {
    connectionState = 4;
    appConn.readChar(connectionId, 0);
  }
  renderOutputs(output);
};

function evaluate(input, output) {
  readWatchClock(true);
  updateWatch(output);
}

function getUserInterface() {
  return { template: unit === 1 ? 'mgdl' : 'mmol' };
}

function onExercisePause(input, output) {
  clockReady = false;
  readWatchClock(false);
  updateWatch(output);
}

function onExerciseContinue(input, output) {
  clockReady = false;
  readWatchClock(false);
  updateWatch(output);
}

function getSummaryOutputs() {
  return [];
}
