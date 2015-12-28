/*******************************************************************************
 * Copyright (c) 2015
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 *******************************************************************************/
package jsettlers.tests.replay;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import jsettlers.TestUtils;
import jsettlers.common.CommonConstants;
import jsettlers.common.map.MapLoadException;
import jsettlers.common.resources.ResourceManager;
import jsettlers.logic.map.save.DirectoryMapLister;
import jsettlers.logic.map.save.DirectoryMapLister.ListedMapFile;
import jsettlers.logic.map.save.IMapListFactory;
import jsettlers.logic.map.save.MapFileHeader;
import jsettlers.logic.map.save.MapList;
import jsettlers.logic.map.save.loader.MapLoader;
import jsettlers.main.replay.ReplayTool;
import jsettlers.tests.utils.CountingInputStream;
import jsettlers.tests.utils.DebugMapLister;

@RunWith(Parameterized.class)
public class AutoReplayIT {
	static {
		CommonConstants.ENABLE_CONSOLE_LOGGING = true;
		CommonConstants.CONTROL_ALL = true;
		CommonConstants.USE_SAVEGAME_COMPRESSION = true;

		TestUtils.setupResourcesManager();
		MapList.setDefaultListFactory(new IMapListFactory() {
			@Override
			public MapList getMapList() {
				File resourceDir = ResourceManager.getSaveDirectory();
				return new MapList(new DirectoryMapLister(new File(resourceDir, "maps")), new DebugMapLister(new File(resourceDir, "save")));
			}
		});
	}

	private static final String REMAINING_REPLAY_FILENAME = "out/remainingReplay.log";
	private static final Object ONLY_ONE_TEST_AT_A_TIME_LOCK = new Object();

	@Parameters(name = "{index}: {0} : {1}")
	public static Collection<Object[]> replaySets() {
		return Arrays.asList(new Object[][] {
				{ "basicProduction-mountainlake", 15 },

				{ "fullProduction-mountainlake", 10 },
				{ "fullProduction-mountainlake", 20 },
				{ "fullProduction-mountainlake", 30 },
				{ "fullProduction-mountainlake", 40 },
				{ "fullProduction-mountainlake", 50 },
				{ "fullProduction-mountainlake", 69 },

				{ "fighting-testmap", 8 }
		});
	}

	private final String folderName;
	private final int targetTimeMinutes;

	public AutoReplayIT(String folderName, int targetTimeMinutes) {
		this.folderName = folderName;
		this.targetTimeMinutes = targetTimeMinutes;
	}

	@Test
	public void testReplay() throws IOException, MapLoadException {
		synchronized (ONLY_ONE_TEST_AT_A_TIME_LOCK) {
			MapLoader actualSavegame = ReplayTool.replayAndGetSavegame(getReplayFile(), targetTimeMinutes, REMAINING_REPLAY_FILENAME);
			MapLoader expectedSavegame = getReferenceSavegamePath();

			compareMapFiles(expectedSavegame, actualSavegame);
			actualSavegame.getFile().delete();
		}
	}

	private MapLoader getReferenceSavegamePath() throws MapLoadException {
		String replayPath = "resources/autoreplay/" + folderName + "/savegame-" + targetTimeMinutes + "m";
		Path uncompressed = Paths.get(replayPath + MapList.MAP_EXTENSION);
		Path compressed = Paths.get(replayPath + MapList.COMPRESSED_MAP_EXTENSION);

		return MapLoader.getLoaderForListedMap(new ListedMapFile((Files.exists(uncompressed) ? uncompressed : compressed).toFile()));
	}

	private File getReplayFile() {
		return new File("resources/autoreplay/" + folderName + "/replay.log");
	}

	private static void compareMapFiles(MapLoader expectedSavegame, MapLoader actualSavegame) throws IOException, MapLoadException {
		System.out.println("Comparing expected '" + expectedSavegame + "' with actual '" + actualSavegame + "' (uncompressed!)");

		try (InputStream expectedStream = MapLoader.getMapInputStream(expectedSavegame.getFile());
				CountingInputStream actualStream = new CountingInputStream(MapLoader.getMapInputStream(actualSavegame.getFile()))) {
			MapFileHeader expectedHeader = MapFileHeader.readFromStream(expectedStream);
			MapFileHeader actualHeader = MapFileHeader.readFromStream(actualStream);

			assertEquals(expectedHeader.getBaseMapId(), actualHeader.getBaseMapId());

			int e, a;
			while (((e = expectedStream.read()) != -1) & ((a = actualStream.read()) != -1)) {
				assertEquals("difference at (uncompressed) byte " + actualStream.getByteCounter(), e, a);
			}
			assertEquals("files have different lengths (uncompressed)", e, a);
		}
	}

	public static void main(String[] args) throws IOException, MapLoadException {
		System.out.println("Creating reference files for replays...");

		for (Object[] replaySet : replaySets()) {
			String folderName = (String) replaySet[0];
			int targetTimeMinutes = (Integer) replaySet[1];

			AutoReplayIT replayIT = new AutoReplayIT(folderName, targetTimeMinutes);
			MapLoader newSavegame = ReplayTool.replayAndGetSavegame(replayIT.getReplayFile(), targetTimeMinutes, REMAINING_REPLAY_FILENAME);
			MapLoader expectedSavegame = replayIT.getReferenceSavegamePath();

			try {
				compareMapFiles(expectedSavegame, newSavegame);
				System.out.println("New savegame is equal to old one => won't replace.");
				newSavegame.getFile().delete();
			} catch (AssertionError | NoSuchFileException | FileNotFoundException ex) { // if the files are not equal, replace the existing one.
				Files.move(Paths.get(newSavegame.getFile().getFile().toString()), Paths.get(expectedSavegame.getFile().getFile().toString()),
						StandardCopyOption.REPLACE_EXISTING);
				System.out.println("Replacing reference file '" + expectedSavegame + "' with new savegame '" + newSavegame + "'");
			}
		}
	}
}
