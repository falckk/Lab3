package amazed.solver;

import amazed.maze.Maze;

import java.util.List;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.*;

/**
 * <code>ForkJoinSolver</code> implements a solver for
 * <code>Maze</code> objects using a fork/join multi-thread
 * depth-first search.
 * <p>
 * Instances of <code>ForkJoinSolver</code> should be run by a
 * <code>ForkJoinPool</code> object.
 */


public class ForkJoinSolver
    extends SequentialSolver
{
  private static ConcurrentSkipListSet<Integer> concurrentVisited=new ConcurrentSkipListSet<Integer>();
  /* This does not have to be shared between the threads, we could let every
  thread have a local map and then add all the elements to the parent thread's
  map when joining but this would severly slowdown joining since each element
  might have to be added several time. */
  private static ConcurrentHashMap<Integer, Integer> concurrentPredecessor = new ConcurrentHashMap<Integer, Integer>();
  private int stepsSinceFork;

    /**
     * Creates a solver that searches in <code>maze</code> from the
     * start node to a goal.
     *
     * @param maze   the maze to be searched
     */
    public ForkJoinSolver(Maze maze)
    {
        super(maze);
        stepsSinceFork=0;
    }

    /**
     * Creates a solver that searches in <code>maze</code> from the
     * start node to a goal, forking after a given number of visited
     * nodes.
     *
     * @param maze        the maze to be searched
     * @param forkAfter   the number of steps (visited nodes) after
     *                    which a parallel task is forked; if
     *                    <code>forkAfter &lt;= 0</code> the solver never
     *                    forks new tasks
     */
    public ForkJoinSolver(Maze maze, int forkAfter)
    {
        this(maze);
        this.forkAfter = forkAfter;
        initStructures();
    }

    /**
     * Searches for and returconcurrenthashmapns the path, as a list of node
     * identifiers, that goes from the start node to a goal node in
     * the maze. If such a path cannot be found (because there are no
     * goals, or all goals are unreacheable), the method returns
     * <code>null</code>.
     *
     * @return   the list of node identifiers from the start node to a
     *           goal node in the maze; <code>null</code> if such a path cannot
     *           be found.
     */
    @Override
    public List<Integer> compute()
    {
        return parallelSearch();
    }

    private List<Integer> parallelSearch()
    {
      // one player active on the maze at start
      int player = maze.newPlayer(start);
      // start with start node
      frontier.push(start);
      // as long as not all nodes have been processed
      while (!frontier.empty()) {
          // get the new node to process
          int current = frontier.pop();
          // if current node has a goal
          if (maze.hasGoal(current)) {
              // move player to goal
              maze.move(player, current);
              // search finished: reconstruct and return path
              return pathFromTo(start, current);
          }
          // if current node has not been visited yet
          if (!concurrentVisited.contains(current)) {
              // move player to current node
              maze.move(player, current);
              // mark node as visited
              concurrentVisited.add(current);
              // for every node nb adjacent to current
              if(stepsSinceFork>=forkAfter){
                ForkJoinSolver[] forkedChildren = new ForkJoinSolver[maze.neighbors(current).size()];
                int child=0;
                for (int nb: maze.neighbors(current)) { //borde kolla om neighbour redan visited
                  // add nb to the nodes to be processed
                  forkedChildren[child]=new ForkJoinSolver(maze, forkAfter);
                  forkedChildren[child].start=nb;
                  forkedChildren[child].fork();
                  // if nb has not been already visited,
                  // nb can be reached from current (i.e., current is nb's predecessor)
                  if (!concurrentVisited.contains(nb))
                      concurrentPredecessor.put(nb, current);
                    }
                    for(int i=0; i<maze.neighbors(current).size(); i++){
                      forkedChildren[i].join();
                    }
                    stepsSinceFork=0;
            }
            for (int nb: maze.neighbors(current)) {
                // add nb to the nodes to be processed
                frontier.push(nb);
                // if nb has not been already visited,
                // nb can be reached from current (i.e., current is nb's predecessor)
                if (!concurrentVisited.contains(nb))
                    predecessor.put(nb, current);
            }
            stepsSinceFork++;
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
