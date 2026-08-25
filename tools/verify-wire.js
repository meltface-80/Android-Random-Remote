#!/usr/bin/env node
/*
 * Check the Kotlin MOO/SOOD encoders against Roon's own reference code.
 *
 * `core/src/test/.../MooTest.kt` and `SoodTest.kt` write the frames they
 * produce to core/build/moo-samples. This script feeds those bytes to
 * node-roon-api's moo.js and sood.js, so the framing is verified against
 * RoonLabs' implementation rather than against one reading of the protocol.
 *
 * Usage:
 *   git clone --depth 1 https://github.com/RoonLabs/node-roon-api /tmp/node-roon-api
 *   ./gradlew :core:test
 *   node tools/verify-wire.js /tmp/node-roon-api
 *
 * It is deliberately NOT part of the Gradle build: the reference checkout is a
 * developer's tool, and a unit test suite that needs a network clone to run is
 * a unit test suite that stops being run.
 */

const fs = require("fs");
const path = require("path");

const roonApiDir = process.argv[2] || "/tmp/node-roon-api";
const sampleDir = process.argv[3] ||
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

if (failures) {
  console.error(`\n${failures} check(s) failed.`);
  process.exit(1);
}
console.log("\nAll wire-format checks passed against node-roon-api.");
