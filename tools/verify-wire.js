#!/usr/bin/env node
/*
 * Check the Kotlin MOO/SOOD encoders against Roon's own reference code.
 *
 * `core/src/test/.../MooTest.kt` and `SoodTest.kt` write the frames they
 * produce to core/build/moo-samples. This script feeds those bytes to
 * node-roon-api's moo.js and sood.js, so the framing is verified against
 * RoonLabs' implementation rather than against one reading of the protocol.
 *
 * It also cross-checks the transport REQUEST BODIES against
 * node-roon-api-transport. Roon identifies the zone a transport request is for
 * with `zone_or_output_id`, and a request that omits it is not refused — the
 * Core answers successfully about nothing. subscribe_queue shipped without it
 * and the queue screen simply read "Queue is empty" forever, which no unit
 * test against a fake Core can catch: the fake takes a zoneId argument and
 * never sees the frame. Comparing the two sources does catch it.
 *
 * Usage:
 *   git clone --depth 1 https://github.com/RoonLabs/node-roon-api /tmp/node-roon-api
 *   git clone --depth 1 https://github.com/RoonLabs/node-roon-api-transport /tmp/node-roon-api-transport
 *   ./gradlew :core:test
 *   node tools/verify-wire.js /tmp/node-roon-api /tmp/node-roon-api-transport
 *
 * It is deliberately NOT part of the Gradle build: the reference checkout is a
 * developer's tool, and a unit test suite that needs a network clone to run is
 * a unit test suite that stops being run.
 */

const fs = require("fs");
const path = require("path");

const roonApiDir = process.argv[2] || "/tmp/node-roon-api";
const transportDir = process.argv[3] || "/tmp/node-roon-api-transport";
const sampleDir = process.argv[4] ||
  path.join(__dirname, "..", "core", "build", "moo-samples");

const quietLogger = { log: () => {} };

const Moo = require(path.join(roonApiDir, "moo.js"));
const moo = new Moo({ moo: null, logger: quietLogger });

let failures = 0;

function check(name, fn) {
  try {
    fn();
    console.log("  ok    " + name);
  } catch (e) {
    failures++;
    console.log("  FAIL  " + name + " — " + e.message);
  }
}

function assertEqual(actual, expected, what) {
  if (actual !== expected) {
    throw new Error(`${what}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
  }
}

function sample(name) {
  const file = path.join(sampleDir, name);
  if (!fs.existsSync(file)) {
    throw new Error(`missing ${file} — run ./gradlew :core:test first`);
  }
  return fs.readFileSync(file);
}

console.log("MOO frames, parsed by node-roon-api/moo.js:");

check("REQUEST with a JSON body", () => {
  const msg = moo.parse(sample("request.bin"));
  if (!msg) throw new Error("moo.js refused the frame");
  assertEqual(msg.verb, "REQUEST", "verb");
  assertEqual(msg.service, "com.roonlabs.transport:2", "service");
  assertEqual(msg.name, "change_volume", "name");
  assertEqual(msg.request_id, "7", "request_id");
  assertEqual(msg.body.output_id, "1701", "body.output_id");
  assertEqual(msg.body.how, "relative_step", "body.how");
  assertEqual(msg.body.value, 1, "body.value");
});

check("COMPLETE with no body", () => {
  const msg = moo.parse(sample("complete.bin"));
  if (!msg) throw new Error("moo.js refused the frame");
  assertEqual(msg.verb, "COMPLETE", "verb");
  assertEqual(msg.name, "Success", "name");
  assertEqual(msg.request_id, "3", "request_id");
});

check("CONTINUE carrying a zones delta", () => {
  const msg = moo.parse(sample("continue.bin"));
  if (!msg) throw new Error("moo.js refused the frame");
  assertEqual(msg.verb, "CONTINUE", "verb");
  assertEqual(msg.name, "Changed", "name");
  assertEqual(msg.body.zones_changed[0].zone_id, "16", "zone_id");
});

// sood.js keeps its parser private, so the query is checked against the same
// layout sood.js writes: "SOOD" | 0x02 | 'Q' | (name_len:u8 name value_len:u16be value)*
console.log("SOOD query, decoded with sood.js's own layout:");

check("query names the Roon service id", () => {
  const buf = sample("sood-query.bin");
  assertEqual(buf.slice(0, 4).toString("utf8"), "SOOD", "magic");
  assertEqual(buf[4], 2, "version");
  assertEqual(String.fromCharCode(buf[5]), "Q", "type");

  const props = {};
  let pos = 6;
  while (pos < buf.length) {
    const nameLen = buf[pos++];
    const name = buf.slice(pos, pos + nameLen).toString("utf8");
    pos += nameLen;
    const valLen = buf.readUInt16BE(pos);
    pos += 2;
    if (valLen === 0xffff) {
      props[name] = null;
    } else {
      props[name] = buf.slice(pos, pos + valLen).toString("utf8");
      pos += valLen;
    }
  }
  assertEqual(pos, buf.length, "consumed every byte");
  assertEqual(props.query_service_id, "00720724-5143-4a9b-abac-0e50cba674bb", "service id");
  if (!props._tid) throw new Error("query carries no _tid");
});

/* --------------------------------------------------------------------------
 * Transport request bodies, against node-roon-api-transport.
 *
 * Every verb RoonLabs sends `zone_or_output_id` with must send it here too.
 * The two files are read as text rather than executed: lib.js builds its
 * bodies inline and RoonCore.kt is Kotlin, so this compares what each source
 * puts next to each verb name.
 * ----------------------------------------------------------------------- */

const transportLib = path.join(transportDir, "lib.js");
if (!fs.existsSync(transportLib)) {
  console.log("\n  skip  transport request bodies — clone node-roon-api-transport to run this");
} else {
  const reference = fs.readFileSync(transportLib, "utf8");
  const kotlin = fs.readFileSync(
    path.join(__dirname, "..", "core", "src", "main", "kotlin",
              "com", "musicd", "lite", "roon", "RoonCore.kt"), "utf8");

  // Each `RoonApiTransport.prototype.<verb> = function ... }` block, paired
  // with whether it mentions zone_or_output_id.
  const needsZone = new Set();
  const verbRe = /RoonApiTransport\.prototype\.(\w+)\s*=\s*function/g;
  const starts = [];
  let m;
  while ((m = verbRe.exec(reference)) !== null) starts.push([m[1], m.index]);
  starts.forEach(([verb, at], i) => {
    const body = reference.slice(at, i + 1 < starts.length ? starts[i + 1][1] : reference.length);
    if (/zone_or_output_id\s*:/.test(body)) needsZone.add(verb);
  });

  check("the reference actually parsed", () => {
    if (needsZone.size < 5) {
      throw new Error(`only found ${needsZone.size} zone-carrying verbs — the parse is wrong, ` +
                      "not the implementation");
    }
  });

  for (const verb of [...needsZone].sort()) {
    // Only verbs this build implements; the rest are legitimately absent.
    const call = new RegExp(`RoonServices\\.TRANSPORT,\\s*"${verb}"([\\s\\S]{0,600})`);
    const found = kotlin.match(call);
    if (!found) continue;
    check(`transport ${verb} names the zone`, () => {
      if (!found[1].includes("zone_or_output_id")) {
        throw new Error(
          `RoonCore.kt sends "${verb}" without zone_or_output_id, which ` +
          "node-roon-api-transport always sends. Roon answers such a request " +
          "successfully and about nothing.");
      }
    });
  }
}

if (failures) {
  console.error(`\n${failures} check(s) failed.`);
  process.exit(1);
}
console.log("\nAll wire-format checks passed against node-roon-api.");
