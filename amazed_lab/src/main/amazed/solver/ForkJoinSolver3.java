package amazed.solver;

import amazed.maze.Maze;

import java.util.List;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.locks.*;
import java.util.concurrent.*;

/**
 * <code>ForkJoinSolver3</code> implements a solver for <code>Maze</code>
 * objects using a fork/join multi-thread depth-first search.
 * <p>
 * Instances of <code>ForkJoinSolver32</code> should be run by a
 * <code>ForkJoinPool</code> object.
 */

// Do not depend on forkAfter.

public class ForkJoinSolver3 extends SequentialSolver {
	/* Remembers which squares are already visited. */
	private static ConcurrentSkipListSet<Integer> concurrentVisited = new ConcurrentSkipListSet<Integer>();
	/*
	 * This does not have to be shared between the threads, we could let every
	 * thread have a local map and then add all the elements to the parent thread's
	 * map when joining but this would severly slowdown joining since each element
	 * might have to be added several time.
	 */
	private static ConcurrentHashMap<Integer, Integer> concurrentPredecessor = new ConcurrentHashMap<Integer, Integer>();
	/*
	 * concurrentStart remembers the square the original thread started on. It is
	 * used to quickly find the path when a goal is found.
	 */
	private static int concurrentStart = -1;
	/*
	 * startIsSet remembers if concurrentStart has been set to a value. This is used
	 * so that concurrentStart is only set by the starting thread.
	 */
	private static boolean startIsSet = false;
	/*
	 * foundGoal is used to signal that one thread has found a goal and thus that
	 * all threads can stop and return.
	 */
	private static boolean foundGoal = false;
	/*
	 * hasPlacedPlayer is used so that the player is only placed once for each
	 * thread.
	 */
	private boolean hasPlacedPlayer = false;

	/**
	 * Creates a solver that searches in <code>maze</code> from the start node to a
	 * goal.
	 *
	 * @param maze the maze to be searched
	 */
	public ForkJoinSolver3(Maze maze) {
		super(maze);
	}

	/**
	 * Creates a solver that searches in <code>maze</code> from the start node to a
	 * goal, forking after a given number of visited nodes.
	 *
	 * @param maze      the maze to be searched
	 * @param forkAfter the number of steps (visited nodes) after which a parallel
	 *                  task is forked; if <code>forkAfter &lt;= 0</code> the solver
	 *                  never forks new tasks
	 */
	public ForkJoinSolver3(Maze maze, int forkAfter) {
		super(maze);
		if (!startIsSet) {
			startIsSet = true;
			concurrentStart = maze.start();
		}
	}

	/**
	 * Searches for and returns the path, as a list of node identifiers, that goes
	 * from the start node to a goal node in the maze. If such a path cannot be
	 * found (because there are no goals, or all goals are unreacheable), the method
	 * returns <code>null</code>.
	 *
	 * @return the list of node identifiers from the start node to a goal node in
	 *         the maze; <code>null</code> if such a path cannot be found.
	 */
	@Override
	public List<Integer> compute() {
		return parallelSearch();
	}

	private List<Integer> parallelSearch() {
		// List of this threads children.
		ArrayList<ForkJoinSolver3> children = new ArrayList<ForkJoinSolver3>();
		int player = -1;
		// start with start node
		frontier.push(start);
		// as long as not all nodes have been processed
		while (!frontier.empty()) {
			// If some other thread has found a goal, join with all your children and
			// return.
			if (foundGoal) {
				for (ForkJoinSolver3 child : children) {
					List<Integer> path = child.join();
					if (path != null) {
						return path;
					}
				}
				return null;
			}
			// get the new node to process
			int current = frontier.pop();
			// if current node has not been visited yet
			if (concurrentVisited.add(current)) {
				// if current node has a goal
				if (maze.hasGoal(current)) {
					// move player to goal
					if (!hasPlacedPlayer) {
						player = maze.newPlayer(current);
						hasPlacedPlayer = true;
					} else {
						maze.move(player, current);
					}
					// search finished: join with children and return path. If none of them has a
					// path to a goal, find the path to your goal.
					foundGoal = true;
					for (ForkJoinSolver3 child : children) {
						List<Integer> path = child.join();
						if (path != null) {
							return path;
						}
					}
					return pathFromTo(concurrentStart, current);
				}
				// move player to current node
				if (!hasPlacedPlayer) {
					player = maze.newPlayer(current);
					hasPlacedPlayer = true;
				} else {
					maze.move(player, current);
				}
				// If we are at an intersection we should fork.
				if (maze.neighbors(current).size() > 2) {
					// hasWork remembers of the parent has been given work.
					boolean hasWork = false;
					// for every node nb adjacent to current
					for (int nb : maze.neighbors(current)) {
						// if nb has not been already visited,
						// nb can be reached from current (i.e., current is nb's predecessor)
						if (!concurrentVisited.contains(nb)) {
							// If the parent does not have work, that thread is given work first.
							if (!hasWork) {
								frontier.push(nb);
								concurrentPredecessor.put(nb, current);
								hasWork = true;
							} else {
								// If the parent does have work, We create a child to search from this square.
								/*
								 * Note: We put before we fork so that the full path to a square that a thread
								 * is on is always in concurrentPredecessor.
								 */
								ForkJoinSolver3 child = new ForkJoinSolver3(maze, forkAfter);
								child.start = nb;
								concurrentPredecessor.put(nb, current);
								children.add(child);
								child.fork();
							}
						}
					}
				} else {
					// If we should not fork we this thread runs like in sequentialSolver.
					for (int nb : maze.neighbors(current)) {
						// add nb to the nodes to be processed
						frontier.push(nb);
						// if nb has not been already visited,
						// nb can be reached from current (i.e., current is nb's predecessor)
						if (!concurrentVisited.contains(nb))
							concurrentPredecessor.put(nb, current);
					}
				}
			}
		}
		/*
		 * If the thread have searched everything in its frontier and not found a goal,
		 * it should join with all its children to see if they have found a goal.
		 */
		for (ForkJoinSolver3 child : children) {
			List<Integer> path = child.join();
			if (path != null) {
				return path;
			}
		}
		// all nodes explored, no goal found
		return null;
	}

	protected List<Integer> pathFromTo(int from, int to) {
		List<Integer> path = new LinkedList<>();
		Integer current = to;
		while (current != from) {
			path.add(current);
			current = concurrentPredecessor.get(current);
			if (current == null)
				return null;
		}
		path.add(from);
		Collections.reverse(path);
		return path;
	}
}
