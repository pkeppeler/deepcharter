import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MOJANG_VERSION_MANIFEST_URL } from '../../src/parsers/version-manifest.js';
import type { VersionJson, VersionManifest } from '../../src/types/minecraft.js';

const { mockFetchJson } = vi.hoisted(() => ({
  mockFetchJson: vi.fn(),
}));

vi.mock('../../src/downloaders/http-client.js', () => ({
  downloadFile: vi.fn(),
  fetchJson: mockFetchJson,
}));

import { MojangDownloader } from '../../src/downloaders/mojang-downloader.js';

const VERSION_ID = 'unit-test-version';
const VERSION_JSON_URL = 'https://example.com/unit-test-version.json';

const TEST_MANIFEST: VersionManifest = {
  latest: {
    release: VERSION_ID,
    snapshot: VERSION_ID,
  },
  versions: [
    {
      id: VERSION_ID,
      type: 'release',
      url: VERSION_JSON_URL,
      time: '2026-01-01T00:00:00+00:00',
      releaseTime: '2026-01-01T00:00:00+00:00',
      sha1: 'manifest-sha1',
      complianceLevel: 1,
    },
  ],
};

const TEST_VERSION_JSON: VersionJson = {
  id: VERSION_ID,
  type: 'release',
  time: '2026-01-01T00:00:00+00:00',
  releaseTime: '2026-01-01T00:00:00+00:00',
  mainClass: 'net.minecraft.client.main.Main',
  downloads: {
    client: {
      sha1: 'client-sha1',
      size: 1,
      url: 'https://example.com/client.jar',
    },
  },
  libraries: [],
};

describe('MojangDownloader', () => {
  beforeEach(() => {
    mockFetchJson.mockReset();
  });

  it('should cache version JSON fetches per version', async () => {
    mockFetchJson.mockImplementation(async (url: string) => {
      if (url === MOJANG_VERSION_MANIFEST_URL) {
        return TEST_MANIFEST;
      }

      if (url === VERSION_JSON_URL) {
        return TEST_VERSION_JSON;
      }

      throw new Error(`Unexpected URL: ${url}`);
    });

    const downloader = new MojangDownloader();

    const [first, second] = await Promise.all([
      downloader.getVersionJson(VERSION_ID),
      downloader.getVersionJson(VERSION_ID),
    ]);

    expect(first).toEqual(TEST_VERSION_JSON);
    expect(second).toEqual(TEST_VERSION_JSON);

    const requestedUrls = mockFetchJson.mock.calls.map(([url]) => String(url));
    expect(requestedUrls.filter((url) => url === MOJANG_VERSION_MANIFEST_URL)).toHaveLength(1);
    expect(requestedUrls.filter((url) => url === VERSION_JSON_URL)).toHaveLength(1);
  });

  it('should clear failed version JSON promises so retries can succeed', async () => {
    let versionJsonAttempts = 0;

    mockFetchJson.mockImplementation(async (url: string) => {
      if (url === MOJANG_VERSION_MANIFEST_URL) {
        return TEST_MANIFEST;
      }

      if (url === VERSION_JSON_URL) {
        versionJsonAttempts += 1;
        if (versionJsonAttempts === 1) {
          throw new Error('Transient version JSON failure');
        }

        return TEST_VERSION_JSON;
      }

      throw new Error(`Unexpected URL: ${url}`);
    });

    const downloader = new MojangDownloader();

    await expect(downloader.getVersionJson(VERSION_ID)).rejects.toThrow(
      'Transient version JSON failure',
    );
    await expect(downloader.getVersionJson(VERSION_ID)).resolves.toEqual(TEST_VERSION_JSON);

    const requestedUrls = mockFetchJson.mock.calls.map(([url]) => String(url));
    expect(requestedUrls.filter((url) => url === MOJANG_VERSION_MANIFEST_URL)).toHaveLength(1);
    expect(requestedUrls.filter((url) => url === VERSION_JSON_URL)).toHaveLength(2);
  });
});
