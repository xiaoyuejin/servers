package org.labrad.qubits;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.labrad.data.Data;
import org.labrad.qubits.channels.Channel;
import org.labrad.qubits.channels.PreampChannel;
import org.labrad.qubits.channels.SramChannel;
import org.labrad.qubits.mem.MemoryCommand;
import org.labrad.qubits.resources.AnalogBoard;
import org.labrad.qubits.resources.DacBoard;
import org.labrad.qubits.resources.MicrowaveBoard;

import com.google.common.base.Preconditions;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

public class Experiment {
	
	public Experiment(List<Device> devices) {
		for (Device dev : devices) {
			addDevice(dev);
		}
		createResourceModels();
	}
	
	
	//
	// Resources
	//
	
	private void createResourceModels() {
		Map<DacBoard, FpgaModel> boards = Maps.newHashMap();
		
		// build models for all required resources
		for (Channel ch : getChannels()) {
			DacBoard board = ch.getDacBoard();
			FpgaModel fpga = boards.get(board);
			if (fpga == null) {
				if (board instanceof AnalogBoard) {
					fpga = new FpgaModelAnalog(board, this);
				} else if (board instanceof MicrowaveBoard) {
					fpga = new FpgaModelMicrowave(board, this);
				} else {
					throw new RuntimeException("Unknown DAC board type for board " + board.getName());
				}
				boards.put(board, fpga);
				addFpga(fpga);
			}
			// connect this channel to the experiment and fpga model
			ch.setExperiment(this);
			ch.setFpgaModel(fpga);
		}
		
		// build lists of FPGA boards that have or don't have a timing channel
		nonTimerFpgas.addAll(getFpgas());
		for (PreampChannel ch : getChannels(PreampChannel.class)) {
			FpgaModel fpga = ch.getFpgaModel();
			timerFpgas.add(fpga);
			nonTimerFpgas.remove(fpga);
		}
	}
	
	
	//
	// Devices
	//
	
	private final List<Device> devices = Lists.newArrayList();
	private final Map<String, Device> devicesByName = Maps.newHashMap();
	
	private void addDevice(Device dev) {
		devices.add(dev);
		devicesByName.put(dev.getName(), dev);
	}
	
	public Device getDevice(String name) {
		Preconditions.checkArgument(devicesByName.containsKey(name),
				"Device '%s' not found.", name);
		return devicesByName.get(name);
	}
	
	private List<Device> getDevices() {
		return devices;
	}
		
	public List<Channel> getChannels() {
		return getChannels(Channel.class);
	}
	
	public <T extends Channel> List<T> getChannels(Class<T> cls) {
		List<T> channels = Lists.newArrayList();
		for (Device dev : devices) {
			channels.addAll(dev.getChannels(cls));
		}
		return channels;
	}

	
	//
	// FPGAs
	//

	private final Set<FpgaModel> fpgas = Sets.newHashSet();
	private final Set<FpgaModel> timerFpgas = Sets.newHashSet();
	private final Set<FpgaModel> nonTimerFpgas = Sets.newHashSet();
	
	public void addFpga(FpgaModel fpga) {
		fpgas.add(fpga);
	}
	
	/**
	 * Get a list of FPGAs involved in this experiment
	 */
	public Set<FpgaModel> getFpgas() {
		return Sets.newHashSet(fpgas);
	}
	
	public Set<FpgaModel> getTimerFpgas() {
		return Sets.newHashSet(timerFpgas);
	}
	
	public Set<FpgaModel> getNonTimerFpgas() {
		return Sets.newHashSet(nonTimerFpgas);
	}
	
	public List<String> getFpgaNames() {
		List<String> boardsToRun = Lists.newArrayList();
		for (FpgaModel fpga : fpgas) {
			boardsToRun.add(fpga.getName());
		}
		return boardsToRun;
	}
	
	private final List<Data> setupPackets = Lists.newArrayList();
	private final List<String> setupState = Lists.newArrayList();
	private List<PreampChannel> timingOrder = null;
	
	/**
	 * Clear all configuration that has been set for this experiment
	 */
	public void clearConfig() {
		// reset setup packets
		clearSetupState();
		
		// clear timing order
		timingOrder = null;
		
		// clear configuration on all channels
		for (Device dev : getDevices()) {
			for (Channel ch : dev.getChannels()) {
				ch.clearConfig();
			}
		}
	}


	private void clearSetupState() {
		setupState.clear();
		setupPackets.clear();
	}
	
	public void setSetupState(List<String> state, List<Data> packets) {
		clearSetupState();
		setupState.addAll(state);
		setupPackets.addAll(packets);
	}
	
	public List<String> getSetupState() {
		return Lists.newArrayList(setupState);
	}
	
	public List<Data> getSetupPackets() {
		return Lists.newArrayList(setupPackets);
	}
	
	public void setTimingOrder(List<PreampChannel> channels) {
		timingOrder = Lists.newArrayList(channels);
	}
	
	/**
	 * Get the order of boards from which to return timing data
	 * @return
	 */
	public List<String> getTimingOrder() {
		List<String> order = Lists.newArrayList();
		for (PreampChannel ch : timingOrder != null ? timingOrder : getChannels(PreampChannel.class)) {
			order.add(ch.getDacBoard().getName());
		}
		return order;
	}


	
	//
	// Memory
	//
	
	/**
	 * Clear the memory content for this experiment
	 */
	public void clearMemory() {
		// all memory state is kept in the fpga models, so we clear them out
		for (FpgaModel fpga : getFpgas()) {
			fpga.clearMemory();
		}
	}
	
	/**
	 * Add bias commands to a set of FPGA boards
	 * @param allCmds
	 */
	public void addBiasCommands(ListMultimap<FpgaModel, MemoryCommand> allCmds, double delay) {
		// find the maximum number of commands on any single fpga board
    	int maxCmds = 0;
    	for (FpgaModel fpga : allCmds.keySet()) {
    		maxCmds = Math.max(maxCmds, allCmds.get(fpga).size());
    	}
    	
    	// add commands for each board, including noop padding and final delay
    	for (FpgaModel fpga : fpgas) {
    		List<MemoryCommand> cmds = allCmds.get(fpga); 
    		if (cmds != null) {
	    		fpga.addMemoryCommands(cmds);
	        	fpga.addMemoryNoops(maxCmds - cmds.size());
    		} else {
    			fpga.addMemoryNoops(maxCmds);
    		}
        	if (delay > 0) {
        		fpga.addMemoryDelay(delay);
        	}
    	}
	}
	
	/**
	 * Add a delay in the memory sequence
	 */
	public void addMemoryDelay(double microseconds) {
		for (FpgaModel fpga : getFpgas()) {
    		fpga.addMemoryDelay(microseconds);
    	}
	}
	
	/**
	 * Call SRAM
	 */
	
	public void callSramBlock(String block) {
		for (FpgaModel fpga : getFpgas()) {
			fpga.callSramBlock(block);
		}
	}
	
	public void callSramDualBlock(String block1, String block2, double delay) {
		for (FpgaModel fpga : getFpgas()) {
			fpga.callSramDualBlock(block1, block2, delay);
		}
	}
	
	/**
	 * Start timer on a set of boards.
	 */
	public void startTimer(List<PreampChannel> channels) {
		Set<FpgaModel> starts = Sets.newHashSet();
		Set<FpgaModel> noops = getTimerFpgas();
		for (PreampChannel ch : channels) {
			FpgaModel fpga = ch.getFpgaModel();
			starts.add(fpga);
			noops.remove(fpga);
		}
		// start the timer on requested boards
		for (FpgaModel fpga : starts) {
			fpga.startTimer();
		}
		// insert a no-op on all other boards
		for (FpgaModel fpga : noops) {
			fpga.addMemoryNoop();
		}
		// start non-timer boards if they have never been started before
		for (FpgaModel fpga : getNonTimerFpgas()) {
			if (!fpga.isTimerStarted()) {
				fpga.startTimer();
			}
		}
	}
	
	/**
	 * Stop timer on a set of boards.
	 */
	public void stopTimer(List<PreampChannel> channels) {
		Set<FpgaModel> stops = Sets.newHashSet();
		Set<FpgaModel> noops = getTimerFpgas();
		for (PreampChannel ch : channels) {
			FpgaModel fpga = ch.getFpgaModel();
			stops.add(fpga);
			noops.remove(fpga);
		}
		// stop the timer on requested boards and non-timer boards
		for (FpgaModel fpga : stops) {
			fpga.stopTimer();
		}
		// insert a no-op on all other boards
		for (FpgaModel fpga : noops) {
			fpga.addMemoryNoop();
		}
		// stop non-timer boards if they are currently running
		for (FpgaModel fpga : getNonTimerFpgas()) {
			if (fpga.isTimerRunning()) {
				fpga.stopTimer();
			}
		}
	}
	
	
	//
	// SRAM
	//
	
	
	private List<String> blocks = Lists.newArrayList();
	private Map<String, Integer> blockLengths = Maps.newHashMap();
	
	public void startSramBlock(String name, long length) {
		blocks.add(name);
		blockLengths.put(name, (int)length);
		
		// start a new block on each SRAM channel
		for (SramChannel ch : getChannels(SramChannel.class)) {
			ch.startBlock(name, length);
		}
	}
	
	public List<String> getBlockNames() {
		return Lists.newArrayList(blocks);
	}
	
	public int getBlockLength(String name) {
		Preconditions.checkArgument(blockLengths.containsKey(name), "SRAM block '%s' is undefined", name);
		return blockLengths.get(name);
	}
	
	/**
	 * Get the proper length for an SRAM block after padding.
	 * The length should be a multiple of 4 and greater or equal to 20.
	 * @param name
	 * @return
	 */
	public int getPaddedBlockLength(String name) {
		int len = getBlockLength(name);
		if (len % 4 == 0 && len >= 20) return len;
		int paddedLen = Math.max(len + ((4 - len % 4) % 4), 20);
		return paddedLen;
	}
	
	public int getBlockStartAddress(String name) {
		int start = 0;
		for (String block : blocks) {
			if (block.equals(name)) {
				return start;
			}
			start += getPaddedBlockLength(block);
		}
		throw new RuntimeException(String.format("Block '%s' not found", name));
	}
	
	public int getBlockEndAddress(String name) {
		int end = 0;
		for (String block : blocks) {
			end += getPaddedBlockLength(block);
			if (block.equals(name)) {
				return end - 1;
			}
		}
		throw new RuntimeException(String.format("Block '%s' not found", name));
	}
}
