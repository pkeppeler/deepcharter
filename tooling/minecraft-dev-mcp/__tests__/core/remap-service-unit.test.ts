import { describe, expect, it, vi } from 'vitest';
import type { TinyRemapper } from '../../src/java/tiny-remapper.js';
import type { MappingService } from '../../src/services/mapping-service.js';
import { RemapService } from '../../src/services/remap-service.js';
import type { VersionManager } from '../../src/services/version-manager.js';

describe('RemapService (unit)', () => {
  it('should fail before downloading JAR for unsupported mappings on unobfuscated versions', async () => {
    const remapService = new RemapService();

    const isVersionUnobfuscated = vi.fn().mockResolvedValue(true);
    const getVersionJar = vi.fn().mockResolvedValue('raw-client.jar');

    Reflect.set(remapService, 'versionManager', {
      isVersionUnobfuscated,
      getVersionJar,
    } as Pick<VersionManager, 'isVersionUnobfuscated' | 'getVersionJar'>);

    await expect(remapService.getRemappedJar('__vitest_unobf_yarn__', 'yarn')).rejects.toThrow(
      /yarn mappings are not supported for unobfuscated/i,
    );

    expect(isVersionUnobfuscated).toHaveBeenCalledWith('__vitest_unobf_yarn__');
    expect(getVersionJar).not.toHaveBeenCalled();
  });

  it('should return the raw client JAR for unobfuscated mojmap requests without remapping', async () => {
    const remapService = new RemapService();

    const isVersionUnobfuscated = vi.fn().mockResolvedValue(true);
    const getVersionJar = vi.fn().mockResolvedValue('raw-client.jar');
    const getMappings = vi.fn();
    const remap = vi.fn();

    Reflect.set(remapService, 'versionManager', {
      isVersionUnobfuscated,
      getVersionJar,
    } as Pick<VersionManager, 'isVersionUnobfuscated' | 'getVersionJar'>);
    Reflect.set(remapService, 'mappingService', { getMappings } as Pick<
      MappingService,
      'getMappings'
    >);
    Reflect.set(remapService, 'tinyRemapper', { remap } as Pick<TinyRemapper, 'remap'>);

    const result = await remapService.getRemappedJar('__vitest_unobf_mojmap__', 'mojmap');

    expect(result).toBe('raw-client.jar');
    expect(getVersionJar).toHaveBeenCalledTimes(1);
    expect(getMappings).not.toHaveBeenCalled();
    expect(remap).not.toHaveBeenCalled();
  });

  it('should continue remapping normally for obfuscated versions', async () => {
    const remapService = new RemapService();

    const isVersionUnobfuscated = vi.fn().mockResolvedValue(false);
    const getVersionJar = vi.fn().mockResolvedValue('input.jar');
    const getMappings = vi.fn().mockResolvedValue('intermediary.tiny');
    const remap = vi.fn().mockResolvedValue(undefined);

    Reflect.set(remapService, 'versionManager', {
      isVersionUnobfuscated,
      getVersionJar,
    } as Pick<VersionManager, 'isVersionUnobfuscated' | 'getVersionJar'>);
    Reflect.set(remapService, 'mappingService', { getMappings } as Pick<
      MappingService,
      'getMappings'
    >);
    Reflect.set(remapService, 'tinyRemapper', { remap } as Pick<TinyRemapper, 'remap'>);

    const result = await remapService.getRemappedJar('__vitest_obf_intermediary__', 'intermediary');

    expect(isVersionUnobfuscated).toHaveBeenCalledWith('__vitest_obf_intermediary__');
    expect(getVersionJar).toHaveBeenCalledTimes(1);
    expect(getMappings).toHaveBeenCalledWith('__vitest_obf_intermediary__', 'intermediary');
    expect(remap).toHaveBeenCalledTimes(1);
    expect(result).toContain('__vitest_obf_intermediary__');
    expect(result).toContain('intermediary');
  });
});
