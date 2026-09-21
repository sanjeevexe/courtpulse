import { execFileSync } from 'node:child_process';
import { readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';

const output = resolve('src/api/generated/schema.ts');
const temporary = resolve(tmpdir(), `courtpulse-openapi-${process.pid}.ts`);
const executable = resolve('node_modules/.bin/openapi-typescript');

try {
  execFileSync(executable, ['../../contracts/openapi/courtpulse-v1.yaml', '-o', temporary], {
    stdio: 'inherit',
  });
  if (readFileSync(output, 'utf8') !== readFileSync(temporary, 'utf8')) {
    throw new Error('Generated API types are stale. Run npm run generate:api.');
  }
  process.stdout.write('Generated API types match the checked OpenAPI contract.\n');
} finally {
  rmSync(temporary, { force: true });
}
