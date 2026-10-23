const path = require('node:path');

module.exports = {
  ...require('../jest.config.cjs'),
  rootDir: path.resolve(__dirname, '..'),
  transform: { '^.+\\.(ts|tsx)$': '<rootDir>/tests/typescript-transformer.cjs' },
};
