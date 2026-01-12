# Que1. Amazon Coding

Each day amazon go stores has some schedule, each schedule will have a start and end time,
deployment can be done when only the stores are closed.
given the current time and schedule for yesterday, today, tomorrow and day after.
find a suitable time to deploy to the stores.

note:
if the store is closed now, we can deploy now
if there is no suitable time, return null/none

yesterday: 08:00 -> 22:00
today: 07:00 -> 23:00
tomorrow: 09:00->23:00
day after: 09:00 -> 23:00

these are open n closing tym of store

input time:
06:00->time to deploy 06:00
7:05 -> 21:00(today)
22:00 -> 22:00(today)


give the best solution with approach n algo in java


# Que 2.

u have collection of cricket scoring display  planks, each with a single charachter on each face, given a batsman playing, determine whether its possible to build the batsman name using the die available

First eg: Die: (S,A), (C,B), (X, H), (I, B), (N,T), (Z, S) Word: SACHIN Expected result: true

second: (S,A), (K,B), (D,Y), (E,P), (E,V) : WORD: KDEV EXPECTED RESULT: true

word: SABY Exp result: false

please give the best solution in java optimised with approach n algo

# Que 3.

Question: There is a remote-controlled car and a drone at coordinates (0,0). A common series of commands is passed where each command is


U -> move object from (x,y) to (x,y+1)
R -> move object from (x,y) to (x+1,y) 
L->move object from (x,y) to (x-1,y)
D-> move object from (x,y) to (x,y-1)

Now for a series of commands determine the distribution of commands to the car & the drone so that they end in the same coordinates. If there are multiple answers then output any. If it is not possible then out

output NO. At least one command should be given to each car & drone.

- Example: URUDUR

in the above problem there's a catch like there should be atleast one command for each car and drone

Answer: CCDCCD { where C means the command is passed to the car & D means command is passed to the drone}


plz give best optimal solution in java, with approach n algo

in the above problem there's a catch like there should be atleast one command for each car and drone

# Que 4. Google Round 1

a list of houses and are grouped with equal size 
two input arrays 
1st- house no. in each neighbourhood
2nd- having respective colours,
u can move houses and to reorganise the house no. in ascending order and it should be hose number should  non repetitive after reorganisation, 
and the structure must be preserved, after reoerganise number of neighbour should be same in each group 
u have to return 

example:

(8. 2, 9), (4, 6, 4), (4, 5, 1} }
((r, g, b), ('w', 'c', 'b'), ('x', 'y', 'b'}} - colours
Here are a couple of possible outputs of how the houses from the example above could be restructured:
{(1, 2, 4), (4, 5, 6), (4, 8, 9}}
{(1, 4, 6), (2, 4, 8), (4, 5, 9} }
output should be like this

{{1b, 4b, 6c), (2g, 4x, 8r). (4w, 5y, 9b} }

hint : can we use object like structure which will store house number and house color in optimize the solution